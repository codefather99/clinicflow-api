import pgserver, psycopg2, threading, uuid, pathlib, sys
srv = pgserver.get_server('/tmp/pgdata', cleanup_mode='delete')
uri = srv.get_uri()
def conn():
    c = psycopg2.connect(uri); c.autocommit = False; return c
def run_file(p):
    c = conn(); c.autocommit = True
    c.cursor().execute(pathlib.Path(p).read_text()); c.close()

for f in ['db/migration/V1__clinicflow_core.sql','db/migration/V100__api_support.sql']: run_file(f)
print("up migrations applied")

c = conn(); cur = c.cursor()
cur.execute("INSERT INTO clinic_day(clinic_date) VALUES ('2026-10-26')")
slot = str(uuid.uuid4())
cur.execute("INSERT INTO slot(id,clinic_date,starts_at,ends_at) VALUES (%s,'2026-10-26','2026-10-26 09:00+01','2026-10-26 09:20+01')",(slot,))
c.commit(); c.close()

def book(n, results, barrier):
    c = conn(); cur = c.cursor()
    barrier.wait()
    try:
        cur.execute("""INSERT INTO appointment(reference,patient_display_name,contact,visit_type_id,clinic_date,slot_id,status)
                       VALUES (%s,%s,'000',1,'2026-10-26',%s,'BOOKED')""",(f"CF-TEST-{n:04d}",f"Patient {n}",slot))
        c.commit(); results.append('ok')
    except psycopg2.errors.UniqueViolation:
        c.rollback(); results.append('refused')
    finally: c.close()

def race(n=8):
    results=[]; b=threading.Barrier(n)
    ts=[threading.Thread(target=book,args=(i,results,b)) for i in range(n)]
    [t.start() for t in ts]; [t.join() for t in ts]
    return results.count('ok'), results.count('refused')

def reset():
    c=conn(); c.autocommit=True; c.cursor().execute("DELETE FROM appointment"); c.close()

print("WITH protection   (ok, refused):", race())
reset()
c=conn(); c.autocommit=True; c.cursor().execute("DROP INDEX ux_appointment_active_slot"); c.close()
print("WITHOUT protection(ok, refused):", race())
reset()
c=conn(); c.autocommit=True; c.cursor().execute("CREATE UNIQUE INDEX ux_appointment_active_slot ON appointment(slot_id) WHERE slot_id IS NOT NULL AND status <> 'CANCELLED'"); c.close()

# cancelled booking frees the slot
c=conn(); cur=c.cursor()
cur.execute("""INSERT INTO appointment(reference,patient_display_name,contact,visit_type_id,clinic_date,slot_id,status,cancelled_at)
               VALUES ('CF-A','A','0',1,'2026-10-26',%s,'CANCELLED',now())""",(slot,))
cur.execute("""INSERT INTO appointment(reference,patient_display_name,contact,visit_type_id,clinic_date,slot_id,status)
               VALUES ('CF-B','B','0',1,'2026-10-26',%s,'BOOKED')""",(slot,))
c.commit(); print("cancelled booking frees slot: OK")

# duplicate queue rank refused
try:
    cur.execute("UPDATE appointment SET status='WAITING', queue_rank=1 WHERE reference='CF-B'"); c.commit()
    cur.execute("""INSERT INTO appointment(reference,patient_display_name,contact,visit_type_id,clinic_date,is_walk_in,status,queue_rank)
                   VALUES ('CF-W','W','0',1,'2026-10-26',true,'WAITING',1)"""); c.commit()
    print("duplicate rank: NOT refused (BAD)")
except psycopg2.errors.UniqueViolation:
    c.rollback(); print("duplicate queue rank refused: OK")

# down then up again
c.close()
run_file('db/down/V100__down.sql'); run_file('db/down/V1__down.sql')
run_file('db/migration/V1__clinicflow_core.sql'); run_file('db/migration/V100__api_support.sql')
print("down + up round trip: OK")
