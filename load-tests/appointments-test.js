
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080';

export const options = {
    scenarios: {
        appointment_booking: {
            executor: 'per-vu-iterations',
            vus: 100,
            iterations: 1,
            maxDuration: '30s',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.01'],
    },
};

// Runs once before the virtual users start
export function setup() {

    const date = __ENV.TEST_DATE ||
        new Date().toISOString().slice(0, 10);

    // Retrieve visit types
    const visitResponse = http.get(
        `${BASE_URL}/api/v1/visit-types`
    );

    check(visitResponse, {
        'visit types retrieved': (r) => r.status === 200,
    });

    const visitTypes = visitResponse.json();

    const generalVisit = visitTypes.find(
        (visit) => visit.code === 'GENERAL'
    );

    if (!generalVisit) {
        throw new Error('GENERAL visit type not found');
    }

    // Retrieve appointment slots
    const slotResponse = http.get(
        `${BASE_URL}/api/v1/slots?date=${date}`
    );

    check(slotResponse, {
        'slots retrieved': (r) => r.status === 200,
    });

    const slots = slotResponse.json();

    // Only use available slots that haven't started yet
    const availableSlots = slots.filter(
        (slot) =>
            slot.available &&
            new Date(slot.startsAt).getTime() > Date.now()
    );

    if (availableSlots.length < 10) {
        throw new Error(
            `Need 10 available future slots. Found: ${availableSlots.length}`
        );
    }

    return {
        visitTypeCode: generalVisit.code,
        slots: availableSlots.slice(0, 10),
    };
}

// Each virtual user executes this once
export default function (data) {

    const slot = data.slots[__VU - 1];

    const payload = {
        patientName: `Load Test Patient ${__VU}`,
        contact: `080${String(__VU).padStart(8, '0')}`,
        visitTypeCode: data.visitTypeCode,
        slotId: slot.id,
    };

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': `k6-booking-${__VU}`,
        },
    };

    const response = http.post(
        `${BASE_URL}/api/v1/patient/appointments`,
        JSON.stringify(payload),
        params
    );

    check(response, {
        'booking returned 201': (r) => r.status === 201,
    });

    if (response.status !== 201) {
        console.log(
            `VU ${__VU}: HTTP ${response.status}: ${response.body}`
        );
    }
}
