import { isValidElement, type ReactElement, type ReactNode } from 'react';

/**
 * Dependency-free test helper: expands a tree of *hook-free* function components into host elements so tests can
 * find elements by props and invoke their real event handlers (no DOM / testing library needed).
 * Components that use hooks must not be passed here – render those with react-dom/server instead.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type HostElement = ReactElement<Record<string, any>, string>;

function* walk(node: ReactNode): Generator<HostElement> {
  if (Array.isArray(node)) {
    for (const n of node) yield* walk(n as ReactNode);
    return;
  }
  if (!isValidElement(node)) return;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const el = node as ReactElement<Record<string, any>>;
  if (typeof el.type === 'function') {
    yield* walk((el.type as (p: unknown) => ReactNode)(el.props));
    return;
  }
  if (typeof el.type === 'string') yield el as HostElement;
  yield* walk(el.props.children as ReactNode);
}

export function findAll(node: ReactNode, pred: (el: HostElement) => boolean): HostElement[] {
  return [...walk(node)].filter(pred);
}

export function findOne(node: ReactNode, pred: (el: HostElement) => boolean): HostElement {
  const all = findAll(node, pred);
  if (all.length !== 1) throw new Error(`expected exactly one match, found ${all.length}`);
  return all[0]!;
}

/** Concatenated text content of an element subtree. */
export function textOf(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map((n) => textOf(n as ReactNode)).join('');
  if (isValidElement(node)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const el = node as ReactElement<Record<string, any>>;
    if (typeof el.type === 'function') return textOf((el.type as (p: unknown) => ReactNode)(el.props));
    return textOf(el.props.children as ReactNode);
  }
  return '';
}
