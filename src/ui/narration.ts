import { createEffect, onCleanup, onMount, useContext } from 'solid-js';
import { activeScope, FocusScope } from './focus';

/** Canvas headings and descriptions remain available to browse-mode screen readers. */
export function narratePage(title: () => string, description: () => string = () => '') {
  const scope = useContext(FocusScope);
  let section: HTMLElement | undefined, heading: HTMLHeadingElement | undefined, detail: HTMLParagraphElement | undefined;
  onMount(() => {
    section = document.createElement('section'); section.className = 'screen-reader-only';
    heading = document.createElement('h1'); detail = document.createElement('p'); section.append(heading, detail);
    document.getElementById('semantics')!.prepend(section);
  });
  createEffect(() => {
    const name = title(), text = description(), hidden = activeScope() !== scope;
    if (section && heading && detail) { heading.textContent = name; detail.textContent = text; section.setAttribute('aria-hidden', String(hidden)); }
  });
  onCleanup(() => section?.remove());
}
