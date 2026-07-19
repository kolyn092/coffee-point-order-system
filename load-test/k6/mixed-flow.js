import { chargePoint, createOrder, findPopularMenus, userForVirtualUser } from './common.js';

const userPrefix = __ENV.USER_PREFIX || 'load-mixed';
const userCount = Number(__ENV.USER_COUNT || 60);

export const options = {
    scenarios: {
        warming_up: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [{ duration: '1m', target: 10 }],
            gracefulRampDown: '0s',
        },
        ten_vus: {
            executor: 'constant-vus',
            vus: 10,
            duration: '3m',
            startTime: '1m',
            gracefulStop: '0s',
        },
        thirty_vus: {
            executor: 'constant-vus',
            vus: 30,
            duration: '3m',
            startTime: '4m',
            gracefulStop: '0s',
        },
        sixty_vus: {
            executor: 'constant-vus',
            vus: 60,
            duration: '3m',
            startTime: '7m',
            gracefulStop: '0s',
        },
    },
};

export default function () {
    const userId = userForVirtualUser(userPrefix, userCount);
    const requestType = __ITER % 10;

    if (requestType < 5) {
        createOrder(userId);
        return;
    }

    if (requestType < 9) {
        findPopularMenus();
        return;
    }

    chargePoint(userId);
}
