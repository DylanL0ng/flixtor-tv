import { children, createMemo, createSignal, Show, type JSX } from 'solid-js';
import { VirtualGrid } from '@solidtv/solid/primitives';
import { Poster } from '../../ui/components';
import { titleKey, type Title, type Viewing } from '../../domain/models';
import { resumeViewing } from './catalog-state';
export function TitleGrid(props: { header?: JSX.Element; interactiveHeader?: boolean; up?(): boolean; titles: Title[]; prefix: string; columns: number; width: number; height: number; initial?: number; autofocus?: boolean; viewing?: Viewing[]; focus?(title: Title, index: number): void; open(title: Title): void; end(): void }) {
  const header = children(() => props.header);
  const cardWidth = () => (props.width - (props.columns - 1) * 28 - 16) / props.columns;
  const rowGap = 28;
  const rowHeight = () => cardWidth() * 1.5 + 78;
  const visibleRows = () => Math.max(1, Math.floor((props.height - 20 + rowGap) / (rowHeight() + rowGap)));
  // The heading occupies a virtual row so one owner scrolls headings and media.
  const headings = Array.from({ length: props.columns }, (_, column) => ({ heading: true as const, column }));
  const headerCount = () => header() ? props.columns : 0;
  const items = createMemo(() => header() ? [...headings, ...props.titles] : props.titles);
  const [focusedIndex, setFocusedIndex] = createSignal(props.initial || 0);
  const viewportHeight = () => header() ? props.height : visibleRows() * rowHeight() + (visibleRows() - 1) * rowGap + 20;
  return <view width={props.width} height={viewportHeight()} clipping={[20, 20, 20, 20]} forwardFocus={0}>
    <VirtualGrid each={items()} x={8} y={10} width={props.width - 16} height={viewportHeight() - 20} gap={rowGap} columns={props.columns} rows={visibleRows()} buffer={1} scroll="always" offset={10} selected={focusedIndex() + headerCount()} wrap={false}
      onUp={() => !!header() && focusedIndex() < props.columns && !!props.up?.()}
      onEndReachedThreshold={props.columns * 2} onEndReached={props.end}>
      {item => <Show when={'heading' in item() ? undefined : item() as Title} fallback={
        <view item={item()} skipFocus={!props.interactiveHeader || (item() as typeof headings[number]).column !== 0} forwardFocus={0} width={cardWidth()} height={82}>{(item() as typeof headings[number]).column === 0 ? header() : undefined}</view>
      }>{title => {
        const index = () => props.titles.findIndex(value => titleKey(value) === titleKey(title()));
        return <Poster title={title()} width={cardWidth()} id={`${props.prefix}:0:${titleKey(title())}`} viewing={props.viewing && resumeViewing(props.viewing, title())}
          autofocus={props.autofocus !== false && (props.initial || 0) === index()} action={() => props.open(title())}
          onFocus={() => { const selected = index(); setFocusedIndex(selected); props.focus?.(title(), selected); if (selected >= props.titles.length - props.columns * 2) props.end(); }} />;
      }}</Show>}
    </VirtualGrid>
  </view>;
}
