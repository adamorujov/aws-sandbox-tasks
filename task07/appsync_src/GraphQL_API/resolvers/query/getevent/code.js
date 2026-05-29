import * as ddb from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
    return ddb.get({ key: { id: ctx.args.id } });
}

export function response(ctx) {
    if (ctx.error) {
        util.error(ctx.error.message, ctx.error.type);
    }
    
    const result = ctx.result;
    
    if (result && result.payLoad && typeof result.payLoad === 'string') {
        result.payLoad = JSON.parse(result.payLoad);
    }
    
    return result;
}