import { chargePoint, createOrder } from './common.js';

const userId = __ENV.CONTENTION_USER_ID || 'load-contention';

export const options = {
    vus: 30,
    duration: '3m',
};

export default function () {
    if (__ITER % 2 === 0) {
        chargePoint(userId);
        return;
    }

    createOrder(userId);
}
