import { util } from '@aws-appsync/utils';
import * as ddb from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
    const id = util.autoId();
    const createdAt = util.time.nowISO8601();
    const { userId, payLoad } = ctx.args;

    const item = {
        id,
        userId,
        createdAt,
        payLoad: JSON.parse(payLoad)
    };

    return ddb.put({
        key: { id },
        item
    });
}

export function response(ctx) {
    return ctx.result;
}
