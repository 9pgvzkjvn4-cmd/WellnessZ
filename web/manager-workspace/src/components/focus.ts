/** Focus the first invalid control after a failed submit (call from an event handler / effect only). */
export function focusFirstError(ids: readonly string[]) {
  for (const id of ids) {
    const el = document.getElementById(id);
    if (el) {
      const target = el.matches('input,select,textarea,button') ? el : el.querySelector<HTMLElement>('input,select,textarea,button');
      (target ?? el).focus();
      return;
    }
  }
}
