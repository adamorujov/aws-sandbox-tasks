import { util } from '@aws-appsync/utils';
import * as ddb from '@aws-appsync/utils/dynamodb';

export function request(ctx) {
  const id = util.autoId();                        // UUIDv4 yaradır
  const createdAt = util.time.nowISO8601();         // Tarixi alır
  const payLoad = JSON.parse(ctx.args.payLoad);     // JSON parse edir

  const item = {
    id,
    userId: ctx.args.userId,
    createdAt,
    payLoad
  };

  return ddb.put({ key: { id }, item });
}

export function response(ctx) {
  if (ctx.error) {
    util.error(ctx.error.message, ctx.error.type);
  }
  return {
    id: ctx.result.id,
    createdAt: ctx.result.createdAt
  };
}