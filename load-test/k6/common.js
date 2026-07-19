import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

export const baseUrl = __ENV.K6_BASE_URL || 'http://load-balancer:8080';
export const orderMenuId = Number(__ENV.ORDER_MENU_ID || 1);
export const pointChargeAmount = Number(__ENV.POINT_CHARGE_AMOUNT || 5000);

const successfulOrders = new Counter('successful_orders');
const successfulOrderPaidAmounts = new Counter('successful_order_paid_amounts');
const successfulCharges = new Counter('successful_charges');
const successfulChargeAmounts = new Counter('successful_charge_amounts');
const successfulPopularReads = new Counter('successful_popular_reads');

export function userForVirtualUser(prefix, count) {
    const userNumber = ((__VU - 1) % count) + 1;
    return `${prefix}-${userNumber}`;
}

export function createOrder(userId) {
    const response = http.post(
        `${baseUrl}/api/v1/orders`,
        JSON.stringify({ userId, menuId: orderMenuId }),
        requestParameters(userId)
    );
    const success = check(response, {
        '주문이 201과 SUCCESS를 반환한다': (result) => isSuccessful(result, 201),
    });

    if (success) {
        successfulOrders.add(1, { user_id: userId });
        successfulOrderPaidAmounts.add(orderMenuId === 1 ? 4500 : 5000, { user_id: userId });
    }
}

export function chargePoint(userId) {
    const response = http.post(
        `${baseUrl}/api/v1/points/charges`,
        JSON.stringify({ userId, amount: pointChargeAmount }),
        requestParameters(userId)
    );
    const success = check(response, {
        '포인트 충전이 200과 SUCCESS를 반환한다': (result) => isSuccessful(result, 200),
    });

    if (success) {
        successfulCharges.add(1, { user_id: userId });
        successfulChargeAmounts.add(pointChargeAmount, { user_id: userId });
    }
}

export function findPopularMenus() {
    const response = http.get(`${baseUrl}/api/v1/menus/popular`);
    const success = check(response, {
        '인기 메뉴 조회가 200과 SUCCESS를 반환한다': (result) => isSuccessful(result, 200),
    });

    if (success) {
        successfulPopularReads.add(1);
    }
}

function requestParameters(userId) {
    return {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: {
            user_id: userId,
        },
    };
}

function isSuccessful(response, expectedStatus) {
    if (response.status !== expectedStatus) {
        return false;
    }

    try {
        return response.json('code') === 'SUCCESS';
    } catch (error) {
        return false;
    }
}
