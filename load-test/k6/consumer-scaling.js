import { createOrder, userForVirtualUser } from './common.js';

const userPrefix = __ENV.USER_PREFIX || 'load-scaling';
const userCount = Number(__ENV.USER_COUNT || 30);
const iterations = Number(__ENV.CONSUMER_SCALING_ITERATIONS || 600);

export const options = {
    scenarios: {
        consumer_scaling: {
            executor: 'shared-iterations',
            vus: 30,
            iterations,
            maxDuration: '5m',
        },
    },
};

export default function () {
    createOrder(userForVirtualUser(userPrefix, userCount));
}
