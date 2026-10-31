"""
Runs every statement from Sql.java against a real Postgres and exercises the flows the real ports depend on,
including the races. The SQL is extracted from the Java source, so what is tested is what ships.
Usage: python verify_real_ports_sql.py <path to Sql.java> <path to migrations dir>
"""
import sys, re, threading, uuid, datetime, pathlib
import pgserver, psycopg2

JAVA, MIG = sys.argv[1], sys.argv[2]

def load_sql(path):
    src = pathlib.Path(path).read_text()
    out = {}
    for m in re.finditer(r'static final String (\w+) =\s*', src):
        i = m.end()
        if src.startswith('"""', i):
            j = src.index('"""', i + 3)
            body = src[i + 3:j]
            body = body.split('\n', 1)[1] if body.startswith('\n') or body.split('\n', 1)[0].strip() == '' else body
        else:
            assert src[i] == '"'
            j = i + 1
            while src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            body = src[i + 1:j]
        body = body.replace('\\\\', '\\')
        out[m.group(1)] = body.replace('?', '%s')
    return out

S = load_sql(JAVA)
print(f"loaded {len(S)} statements from Sql.java")

srv = pgserver.get_server('/tmp/pgdata_real', cleanup_mode='delete'); URI = srv.get_uri()
def conn(ac=False):
    c = psycopg2.connect(URI); c.autocommit = ac; return c
def admin(sql, params=None):
    c = conn(True); cur = c.cursor(); cur.execute(sql, params); r = cur.fetchall() if cur.description else None; c.close(); return r

for f in ['V1__clinicflow_core.sql', 'V100__api_support.sql']:
    admin(pathlib.Path(MIG, f).read_text())

ok = 0
def check(cond, msg):
    global ok; ok += 1
    if not cond: raise AssertionError('FAIL: ' + msg)
    print('  ok ', msg)

D = datetime.date.today() + datetime.timedelta(days=2)
if D.weekday() == 6: D += datetime.timedelta(days=1)

print("Calendar")
for _ in range(2):  # twice: must be idempotent
    admin(S['ENSURE_DAY'], (D, True)); admin(S['ENSURE_SLOTS'], (D, D, D))
n = admin("SELECT count(*) FROM slot WHERE clinic_date=%s", (D,))[0][0]
check(n == 40, f"ensureCalendar creates 40 slots and is idempotent (got {n})")
first, last = admin("SELECT min(starts_at AT TIME ZONE 'Africa/Lagos'), max(starts_at AT TIME ZONE 'Africa/Lagos') FROM slot WHERE clinic_date=%s", (D,))[0]
check(first.hour == 8 and first.minute == 0 and last.hour == 17 and last.minute == 45, f"slots run 08:00 to 17:45 Lagos time ({first.time()}..{last.time()})")
admin(S['ENSURE_DAY'], (D + datetime.timedelta(days=30), False))
check(admin(S['SLOTS_FOR'], (D + datetime.timedelta(days=30),)) == [], "closed day has no slots")
slots = admin(S['SLOTS_FOR'], (D,))
check(len(slots) == 40 and all(r[3] for r in slots), "slotsFor lists 40 free slots")
check(admin(S['VISIT_TYPES'])[0][1] == 'GENERAL', "visit types readable")

def ref(): return 'CF-' + uuid.uuid4().hex[:4].upper() + '-' + uuid.uuid4().hex[:4].upper()
def book(cur, slot_id, name='P', day=D):
    cur.execute(S['INSERT_BOOKING'], (ref(), 'h' * 64, name, '0800', 1, day, slot_id)); return cur.fetchone()[0]

print("Booking race: the rule")
def race_book(slot_id, n=8):
    res, bar = [], threading.Barrier(n)
    def w(i):
        c = conn(); cur = c.cursor(); bar.wait()
        try: book(cur, slot_id, f'R{i}'); c.commit(); res.append('ok')
        except psycopg2.errors.UniqueViolation as e:
            c.rollback(); res.append('taken' if 'ux_appointment_active_slot' in str(e) else 'other-unique')
        c.close()
    ts = [threading.Thread(target=w, args=(i,)) for i in range(n)]
    [t.start() for t in ts]; [t.join() for t in ts]; return res
slot0 = slots[0][0]
res = race_book(slot0)
check(res.count('ok') == 1 and res.count('taken') == 7, f"8 simultaneous bookings, protection ON: {res.count('ok')} booked, {res.count('taken')} refused by ux_appointment_active_slot")
admin("DELETE FROM appointment")
admin("DROP INDEX ux_appointment_active_slot")
res = race_book(slot0)
check(res.count('ok') == 8, f"same race, protection REMOVED: {res.count('ok')} booked (the double-booking bug)")
admin("DELETE FROM appointment")
admin("CREATE UNIQUE INDEX ux_appointment_active_slot ON appointment (slot_id) WHERE slot_id IS NOT NULL AND status <> 'CANCELLED'")

print("Suggestions")
c = conn(True); cur = c.cursor(); book(cur, slots[10][0], 'Hold'); c.close()
sug = admin(S['SUGGESTIONS'], (D, slots[10][0], slots[10][1]))
check(len(sug) == 3 and all(s[0] != slots[10][0] for s in sug), "3 nearest free slots offered, never the taken one")
check({sug[0][0], sug[1][0]} == {slots[9][0], slots[11][0]}, "the two nearest are the neighbours either side")
admin("DELETE FROM appointment")

print("Check-in, positions, cancel, walk-in")
ids = []
c = conn(True); cur = c.cursor()
for i in range(8): ids.append(book(cur, slots[20 + i][0], f'Patient {i}'))
c.close()
def checkin(appt_id, barrier=None):
    c = conn(); cur = c.cursor()
    if barrier: barrier.wait()
    cur.execute(S['LOCK_DAY'], (D,)); cur.execute(S['LOCK_APPOINTMENT'], (appt_id,))
    cur.execute(S['NEXT_BACK_RANK'], (D,)); rank = cur.fetchone()[0]
    cur.execute(S['TO_WAITING'], (rank, appt_id))
    cur.execute(S['ACTIVITY_INSERT'], (appt_id, 'CHECKED_IN', 'BOOKED', 'WAITING', 'Grace', 'RECEPTIONIST', 'req'))
    c.commit(); c.close()
bar = threading.Barrier(8); ts = [threading.Thread(target=checkin, args=(i, bar)) for i in ids]
[t.start() for t in ts]; [t.join() for t in ts]
pos = sorted(r[0] for r in admin("SELECT queue_pos FROM (" + S['BASE'] + ") q WHERE queue_pos IS NOT NULL".replace('SELECT a.id', 'SELECT a.id') ) ) if False else None
rows = admin(S['BASE'] + S['WHERE_DAY_WAITING_ORDER'], (D,))
cols = ['id','reference','name','contact','vt_id','vt_code','vt_label','vt_min','slot_id','starts','ends','walk','status','rank','ci','cs','cc','cx','cr','ver','cat','uat','date','pos']
P = lambda r: dict(zip(cols, r))
positions = [P(r)['pos'] for r in rows]
ranks = [P(r)['rank'] for r in rows]
check(positions == list(range(1, 9)) and len(set(ranks)) == 8, f"8 simultaneous check-ins: positions {positions}, all ranks distinct")

third = P(rows[2])['id']
c = conn(); cur = c.cursor()
cur.execute(S['LOCK_DAY'], (D,)); cur.execute(S['LOCK_APPOINTMENT'], (third,)); cur.execute(S['TO_CANCELLED'], ('left', third)); c.commit(); c.close()
rows = admin(S['BASE'] + S['WHERE_DAY_WAITING_ORDER'], (D,))
check([P(r)['pos'] for r in rows] == list(range(1, 8)), "cancel mid-queue: everyone behind moves up, no gap, no renumbering UPDATE")
check(admin(S['BASE'] + S['WHERE_ID'], (third,))[0][12] == 'CANCELLED' and admin(S['BASE'] + S['WHERE_ID'], (third,))[0][23] is None, "cancelled patient has no position")

c = conn(); cur = c.cursor()
cur.execute(S['LOCK_DAY'], (D,)); cur.execute(S['NEXT_FRONT_RANK'], (D,)); fr = cur.fetchone()[0]
cur.execute(S['INSERT_WALK_IN'], (ref(), 'Walker', '0800', 1, D, fr)); wid = cur.fetchone()[0]; c.commit(); c.close()
check(fr == 0, "first walk-in ahead of queue gets front rank 0")
rows = admin(S['BASE'] + S['WHERE_DAY_WAITING_ORDER'], (D,))
check(P(rows[0])['id'] == wid and P(rows[0])['pos'] == 1 and P(rows[1])['pos'] == 2, "walk-in ahead is position 1, the rest shift by one")
c = conn(); cur = c.cursor(); cur.execute(S['NEXT_FRONT_RANK'], (D,)); check(cur.fetchone()[0] == -1, "second walk-in ahead gets rank -1 (always in front)"); c.rollback(); c.close()

print("Stale check support, activity, estimate")
r0 = P(admin(S['BASE'] + S['WHERE_REFERENCE'], (P(rows[1])['reference'],))[0])
check(r0['status'] == 'WAITING' and r0['ver'] == 1, "reads back status and version for the stale check")
check(len(admin(S['ACTIVITY_FOR'], (P(rows[1])['id'],))) == 1 and len(admin(S['ACTIVITY_RECENT'], (5,))) == 5, "activity history readable per appointment and recent")
for k in range(3):  # three completed consultations of 10, 20 and 30 minutes
    aid = book(conn(True).cursor(), slots[30 + k][0], f'Done {k}')
    admin("UPDATE appointment SET status='COMPLETED', queue_rank=%s, consultation_started_at=now()-interval '60 minutes', completed_at=now()-interval '60 minutes'+make_interval(mins=>%s) WHERE id=%s", (100 + k, 10 * (k + 1), aid))
st = admin(S['COMPLETED_STATS'], (D,))[0]
check(st[0] == 3 and abs(float(st[1]) / 60 - 20) < 0.01, f"completed stats: {st[0]} visits averaging {float(st[1])/60:.0f} minutes")

print("Search, dashboard")
q = S['BASE'] + S['SEARCH_WHERE'] + " AND a.status IN (%s,%s)" + S['SEARCH_AND_Q'] + S['SEARCH_ORDER_PAGE']
res = admin(q, (D, 'WAITING', 'BOOKED', '%patient 1%', 'x', 20, 0))
check(len(res) == 1 and 'Patient 1' in res[0][2], "search by name (case-insensitive) with a status filter")
res = admin(q, (D, 'WAITING', 'BOOKED', '%100\\%%', P(rows[0])['reference'], 20, 0))
check(len(res) == 1 and res[0][1] == P(rows[0])['reference'], "search by exact reference; a literal % in the query is escaped")
check(admin(S['SEARCH_COUNT'] + S['SEARCH_AND_Q'].replace('a.patient_display_name', 'a.patient_display_name'), (D, '%walker%', 'x'))[0][0] == 1, "count query agrees with the filters")
dash = dict(admin(S['DASH_COUNTS'], (D,)))
check(dash.get('WAITING') == 8 and dash.get('CANCELLED') == 1 and dash.get('COMPLETED') == 3, f"dashboard counts derived from records {dash}")
check(admin(S['DASH_WALK_INS'], (D,))[0][0] == 1, "walk-in count")

print("Reschedule")
a, b = ids[0], ids[1]
c = conn(); cur = c.cursor()
try:
    cur.execute(S['RESCHEDULE'], (slots[21][0], D, a)); c.commit(); check(False, "reschedule onto a taken slot should fail")
except psycopg2.errors.UniqueViolation as e:
    c.rollback(); check('ux_appointment_active_slot' in str(e), "reschedule onto a taken slot refused; old slot untouched")
cur.execute(S['RESCHEDULE'], (slots[38][0], D, a)); c.commit(); c.close()
check(admin("SELECT slot_id FROM appointment WHERE id=%s", (a,))[0][0] == slots[38][0], "reschedule onto a free slot works")

print("Seed statements satisfy the table's rules")
admin(S['DELETE_DAY_ACTIVITY'], (D,)); admin(S['DELETE_DAY_APPOINTMENTS'], (D,)); admin(S['RESET_RANKS'], (D,))
check(admin(S['COUNT_DAY'], (D,))[0][0] == 0, "day cleared (activity first, then appointments)")
def seed(name, vt, slot, walk, status, rank, ci, cs, cc, cx, why=None):
    return admin(S['INSERT_SEED'], (ref(), 'h' * 64, name, vt, D, slot, walk, status, rank, ci, ci, cs, cs, cc, cc, cx, cx, why))[0][0]
seed('Done', 1, slots[0][0], False, 'COMPLETED', 1, 200, 195, 187, None)
seed('Now', 2, slots[1][0], False, 'IN_CONSULTATION', 2, 100, 5, None, None)
seed('Wait', 3, slots[2][0], False, 'WAITING', 3, 30, None, None, None)
seed('Booked', 4, slots[3][0], False, 'BOOKED', None, None, None, None, None)
seed('Cancelled mid-queue', 1, slots[4][0], False, 'CANCELLED', 4, 40, None, None, 10, 'left')
seed('Cancelled then rebooked', 1, slots[5][0], False, 'CANCELLED', None, None, None, None, 60, 'called')
seed('Rebooked', 1, slots[5][0], False, 'BOOKED', None, None, None, None, None)
seed('X' * 120, 1, slots[6][0], False, 'BOOKED', None, None, None, None, None)
seed("Ọlá D'Souza-Akinwumi", 1, slots[7][0], False, 'BOOKED', None, None, None, None, None)
seed('Walk ahead', 1, None, True, 'WAITING', 0, 20, None, None, None)
check(admin(S['COUNT_DAY'], (D,))[0][0] == 10, "seed rows with every status insert cleanly (CHECK constraints satisfied)")
admin(S['SET_RANKS'], (-1, 5, D))
check(admin("SELECT front_rank, back_rank FROM clinic_day WHERE clinic_date=%s", (D,))[0] == (-1, 5), "rank counters reset after seeding")
try:
    admin(S['INSERT_SEED'], (ref(), 'h' * 64, 'Dup rank', 1, D, slots[9][0], False, 'WAITING', 3, 10, 10, None, None, None, None, None, None, None)); check(False, "dup rank")
except psycopg2.errors.UniqueViolation:
    check(True, "a duplicate queue rank is refused by the database")
print(f"\nALL {ok} CHECKS PASSED")
