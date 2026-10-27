export default async function* reporter(source) {
  for await (const event of source) {
    yield JSON.stringify(event, (_key, value) => value instanceof Error ? {
      name: value.name, message: value.message, stack: value.stack, code: value.code,
      failureType: value.failureType, cause: value.cause, operator: value.operator,
      actual: value.actual, expected: value.expected,
    } : value) + '\n';
  }
}
