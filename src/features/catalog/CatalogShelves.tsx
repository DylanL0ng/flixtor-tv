import { children, createEffect, createSignal, For, on, onCleanup, Show, untrack, type JSX } from 'solid-js';
import { ElementNode } from '@solidtv/solid';
import { Column, Row, VirtualRow, type NavigableElement } from '@solidtv/solid/primitives';
import { Button, Poster, Status } from '../../ui/components';
import { MaskedViewport } from '../../ui/MaskedViewport';
import { activeElement, activeScope, focusElement, nodeRect } from '../../ui/focus';
import { ContentError } from '../../ui/ContentError';
import { titleKey, type Title, type Viewing } from '../../domain/models';
import type { DiscoveryTitle } from '../../services/cinemeta';
import { resumeViewing } from './catalog-state';

export type ShelfTitle = Title | DiscoveryTitle;
export const isDiscovery = (title: ShelfTitle): title is DiscoveryTitle => title.type === 'movie' || title.type === 'series';
export function shelfTitleKey(title: ShelfTitle) {
  return isDiscovery(title) ? `cinemeta:${title.type}:${title.id}` : titleKey(title);
}
export interface ShelfPosition { title: string; item: number }
export interface BrowseOrigin {
  rail: number; item: number; focus: string; shelf?: string; title?: string;
  positions?: Record<string, ShelfPosition>;
}
export interface CatalogShelf {
  id: string; label: string; titles: ShelfTitle[]; continuing?: boolean;
  pending?: boolean; fetching?: boolean; error?: unknown; retry?(): unknown | Promise<unknown>; end?(): void;
  viewAll?(): void;
}

/** Shared Home/browse navigation. Vertical moves never shift a rail's horizontal window. */
export function CatalogShelves(props: {
  shelves: CatalogShelf[]; prefix: string; height: number; origin?: BrowseOrigin; header?: JSX.Element;
  viewing: Viewing[]; autofocus?: boolean;
  focus(shelf: string, title: ShelfTitle, index: number, rail: number, id: string): void;
  open(title: ShelfTitle, id: string): void;
}) {
  const nodes = new Map<string, NavigableElement>();
  const containers = new Map<string, ElementNode>();
  const header = children(() => props.header);
  const [entering, setEntering] = createSignal<string>();
  let preferredX: number | undefined;
  let activeRail: string | undefined;
  const initialRail = () => Math.max(0, props.shelves.findIndex(s => s.id === props.origin?.shelf));
  function move(id: string, direction: number) {
    const start = props.shelves.findIndex(s => s.id === id);
    for (let index = start + direction; index >= 0 && index < props.shelves.length; index += direction) {
      const target = props.shelves[index], node = nodes.get(target.id);
      if (!node) {
        if (target.error) { containers.get(target.id)?.setFocus(); return true; }
        continue;
      }
      const source = nodeRect(activeElement(), false), viewport = nodeRect(node.parent, false);
      const x = preferredX ?? source.x + source.width / 2;
      const visible = node.children.filter((child): child is ElementNode => {
        if (!(child instanceof ElementNode) || child.skipFocus) return false;
        const rect = nodeRect(child, false);
        return rect.x >= viewport.x - 1 && rect.x + rect.width <= viewport.x + viewport.width + 1;
      });
      const closest = visible.reduce<ElementNode | undefined>((best, child) => {
        const rect = nodeRect(child, false), previous = nodeRect(best, false);
        return !best || Math.abs(rect.x + rect.width / 2 - x) < Math.abs(previous.x + previous.width / 2 - x) ? child : best;
      }, undefined);
      if (!closest) continue;
      setEntering(target.id); focusElement(closest); setEntering(undefined);
      return true;
    }
    return direction > 0; // Up from first shelf returns to header/navigation.
  }
  return <Column width={1728} height={props.height} gap={30} scroll="always" offset={24}
    selected={props.origin ? initialRail() + (header() ? 1 : 0) : 0}>
    {header()}
    <ContentError screenKey={props.prefix} operations={props.shelves.map(shelf => ({ id: shelf.id, error: shelf.error, fetching: shelf.fetching, retry: () => shelf.retry?.() }))} />
    <For each={props.shelves.map(shelf => shelf.id)}>{(id, rail) => {
      const shelf = () => props.shelves.find(s => s.id === id)!;
      const focusId = (title: ShelfTitle) => `${props.prefix}:${id}:${shelfTitleKey(title)}`;
      const saved = untrack(() => props.origin?.positions?.[id] || (props.origin?.shelf === id ? { title: props.origin.title || '', item: props.origin.item } : undefined));
      let focusedKey = saved?.title;
      let focusedIndex = saved?.item || 0;
      let focusedOnce = false;
      let restoreFrame = 0;
      onCleanup(() => cancelAnimationFrame(restoreFrame));
      const horizontalBoundary = (direction: number) => {
        const node = nodes.get(id), active = activeElement();
        if (node && active?.parent === node) node.selected = node.children.indexOf(active);
        const index = shelf().titles.findIndex(title => shelfTitleKey(title) === focusedKey);
        return index + direction < 0 || index + direction >= shelf().titles.length;
      };
      const initial = () => {
        const found = shelf().titles.findIndex(t => shelfTitleKey(t) === focusedKey);
        return found >= 0 ? found : Math.min(saved?.item || 0, Math.max(0, shelf().titles.length - 1));
      };
      const [delayed, setDelayed] = createSignal(false);
      createEffect(() => {
        const fetching = !!shelf().fetching;
        if (!fetching) setDelayed(false);
        const timer = setTimeout(() => setDelayed(fetching), 120); onCleanup(() => clearTimeout(timer));
      });
      // A bounded query may evict old pages: keep the same domain title under focus.
      createEffect(on(() => shelf().titles, titles => {
        const node = nodes.get(id);
        if (!node || !focusedKey) return;
        const retained = titles.findIndex(t => shelfTitleKey(t) === focusedKey);
        const index = retained >= 0 ? retained : Math.min(focusedIndex, Math.max(0, titles.length - 1));
        const current = activeElement();
        const ownsFocus = current?.parent === node || activeRail === id && !current?.rendered;
        if (ownsFocus) {
          cancelAnimationFrame(restoreFrame);
          // VirtualRow updates its mounted window in microtasks. Restore after those settle.
          restoreFrame = requestAnimationFrame(() => {
            if (nodes.get(id) !== node || !node.rendered || !titles.length || activeScope() !== 'screen') return;
            const active = activeElement();
            if (active?.rendered && active.parent !== node) return;
            // The old card may still reference its parent after its scene node died.
            // Only synchronize a selection that is actually in the mounted window.
            if (Number(node.cursor) === index && focusedKey === shelfTitleKey(titles[index]) && active?.rendered && active.parent === node && node.children.includes(active)) {
              // Appending changes VirtualRow's window without moving the focused node.
              // Align its local selection so the next arrow advances exactly one item.
              node.selected = node.children.indexOf(active);
            } else {
              focusedKey = shelfTitleKey(titles[index]); focusedIndex = index;
              node.scrollToIndex?.(index);
            }
          });
        }
      }, { defer: true }));
      return <Column width={1728} height={(shelf().continuing ? 320 : 480) + 72} gap={24} scroll="none"
        ref={node => { containers.set(id, node); onCleanup(() => containers.delete(id)); }}
        onUp={() => !shelf().titles.length && move(id, -1)} onDown={() => !shelf().titles.length && move(id, 1)}>
        <Row width={1728} height={48} gap={24} skipFocus={!shelf().viewAll}>
          <text skipFocus width={1100} height={48} fontSize={36} fontFamily="Roboto700" maxLines={1}>{shelf().label}</text>
          <Show when={shelf().viewAll}><Button label={shelf().continuing ? 'View Continue Watching' : `View all ${shelf().label}`}  height={48} width={370} action={() => shelf().viewAll?.()} /></Show>
          <Show when={delayed()}><Status width={400} message="Refreshing…" /></Show>
        </Row>
        <Show when={shelf().titles.length} fallback={<Status message={shelf().pending ? 'Loading catalogue…' : shelf().error ? '' : 'No titles found.'} error={!!shelf().error} />}>
          <MaskedViewport width={1728} height={shelf().continuing ? 320 : 480}>
            <VirtualRow each={shelf().titles} x={24} width={1680} height={shelf().continuing ? 320 : 480} gap={28}
              displaySize={shelf().continuing ? 4 : 6} bufferSize={2} scroll={entering() === id ? 'none' : 'edge'} animationSettings={{ duration: 100 }} wrap={false}
              ref={node => { nodes.set(id, node as NavigableElement); onCleanup(() => nodes.delete(id)); }}
              onUp={() => move(id, -1)} onDown={() => move(id, 1)}
              onLeft={() => horizontalBoundary(-1)}
              onRight={() => horizontalBoundary(1)} selected={untrack(initial)}>
              {title => {
                // VirtualRow indexes describe its mounted window, not the full shelf.
                const index = () => shelf().titles.findIndex(item => shelfTitleKey(item) === shelfTitleKey(title()));
                return <Poster title={title()} width={shelf().continuing ? 390 : 250} landscape={shelf().continuing}
                viewing={!isDiscovery(title()) ? resumeViewing(props.viewing, title() as Title) : undefined}
                id={focusId(title())} autofocus={!focusedOnce && (props.origin ? props.origin.shelf === id && initial() === index() : !!props.autofocus && rail() === 0 && index() === 0)}
                action={() => props.open(title(), focusId(title()))}
                onFocus={node => {
                  focusedOnce = true; activeRail = id; focusedIndex = index();
                  focusedKey = shelfTitleKey(title());
                  if (!entering()) { const rect = nodeRect(node, false); preferredX = rect.x + rect.width / 2; }
                  props.focus(id, title(), index(), rail(), focusId(title()));
                  if (!shelf().continuing && index() >= shelf().titles.length - 4) shelf().end?.();
                }} />;
              }}
            </VirtualRow>
          </MaskedViewport>
        </Show>
      </Column>;
    }}</For>
  </Column>;
}
