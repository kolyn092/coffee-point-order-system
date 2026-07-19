import { createOrder, userForVirtualUser } from './common.js';

const userPrefix = __ENV.USER_PREFIX || 'load-kafka';
const userCount = Number(__ENV.USER_COUNT || 10);

export const options = {
    vus: 10,
    duration: '3m',
};

export default function () {
    createOrder(userForVirtualUser(userPrefix, userCount));
}
