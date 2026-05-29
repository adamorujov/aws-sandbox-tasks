import { util } from '@aws-appsync/utils';
import { put } from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
  const id = util.autoId();
  const createdAt = util.time.nowISO8601();

  return put({
    key: { id },
    item: {
      userId: ctx.arguments.userId,
      createdAt: createdAt,
      payLoad: ctx.arguments.payLoad
    }
  });
}

export function response(ctx) {
  if (ctx.error) {
    util.error(ctx.error.message, ctx.error.type);
  }
  return ctx.result;
}