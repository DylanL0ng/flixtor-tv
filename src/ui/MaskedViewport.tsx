import { onCleanup, onMount, type ParentProps } from 'solid-js';
import type { ElementNode } from '@solidtv/solid';
import { gradientMaskOwners, viewportClipMargin } from './gradient-mask';

/** Fixed viewport ramps; only the children move during scrolling. */
export function MaskedViewport(props: ParentProps<{ width: number; height: number; vertical?: boolean; onUp?(): boolean }>) {
  let viewport: ElementNode | undefined;
  onMount(() => {
    const id = (viewport?.lng as { id?: number } | undefined)?.id;
    if (id === undefined) throw new Error('Gradient masks require the SolidTV WebGL renderer');
    gradientMaskOwners.set(id, props.vertical ? [0, 24, 0, 96] : [56, 0, 56, 0]);
    onCleanup(() => gradientMaskOwners.delete(id));
  });
  return <view ref={viewport} width={props.width} height={props.height} clipping={[viewportClipMargin, viewportClipMargin, viewportClipMargin, viewportClipMargin]} onUp={props.onUp} forwardFocus={0}>{props.children}</view>;
}
