import type { FieldError } from './devices';

/** First error message for a field id. */
export const errorFor = (errors: readonly FieldError[], field: string): string | undefined => errors.find((e) => e.field === field)?.message;
