import { util } from '@aws-appsync/utils';
import * as ddb from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
    const id = util.autoId();
    const createdAt = util.time.nowISO8601();
    const { userId, payLoad } = ctx.args;

    return ddb.put({
        key: { id },
        item: {
            id,
            userId,
            createdAt,
            payLoad: payLoad
        }
    });
}

export function response(ctx) {
    if (ctx.error) {
        util.error(ctx.error.message, ctx.error.type);
    }
    return ctx.result;
}