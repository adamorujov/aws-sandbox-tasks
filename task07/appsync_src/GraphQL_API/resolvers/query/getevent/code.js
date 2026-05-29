import * as ddb from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
    return ddb.get({ key: { id: ctx.args.id } });
}

export function response(ctx) {
    return ctx.result;
}