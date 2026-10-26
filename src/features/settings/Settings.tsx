import { createSignal, Show, For } from 'solid-js';
import { createQuery } from '@tanstack/solid-query';
import { Column, Row } from '@solidtv/solid/primitives';
import { useLocation, useNavigate } from '@solidjs/router';
import { useApp } from '../../app/context';
import { Avatar, Button, Glass, Menu, palette, type Option } from '../../ui/components';
import { Overlay } from '../../ui/Overlay';
import { narratePage } from '../../ui/narration';
import { titleKey } from '../../domain/models';

export function Settings() {
  const app = useApp(), location = useLocation(), navigate = useNavigate();
  const [confirm, setConfirm] = createSignal<{ label: string; action(): Promise<unknown> }>();
  const [working, setWorking] = createSignal(false), [confirmError, setConfirmError] = createSignal('');
  const [fit, setFit] = createSignal<{ horizontal: number; vertical: number }>();
  const section = () => location.pathname.split('/')[2] || '';
  const p = () => app.profile()!;
  const account = createQuery(() => ({ queryKey: ['account', app.authRevision()], queryFn: ({ signal }) => app.flixtor.account(signal), retry: false }));
  const accountLabel = () => account.isPending ? 'Checking account…' : account.isError || account.data === 'UNAVAILABLE' ? 'Account unavailable' : account.data === 'VIP' ? 'VIP active' : account.data === 'SIGNED_IN' ? 'Signed in · VIP unverified' : account.data === 'EXPIRED' ? 'Membership expired' : 'Guest';
  narratePage(() => 'Settings', () => `${p()?.name || ''}. ${accountLabel()}`);
  const run = app.run;
  const input = async (label: string, current: string, save: (value: string) => Promise<unknown>, secret = false) => { const value = await app.input(label, current, secret); if (value !== null) await save(value); };
  const options = (): Option[] => {
    const s = section();
    if (!s) return ['Account', 'Profiles', 'Playback', 'Skip segments', 'Updates', 'Display', 'Library'].map(label => ({ id: label, label, action: () => navigate('/settings/' + label.toLowerCase().replace(' segments', '')) }));
    if (s === 'account') return [
      { id: 'status', label: 'Refresh account status', action: () => { app.refreshAccount(); app.notify('Checking account…'); } },
      { id: 'pair', label: 'Sign in with phone', action: () => navigate('/pair') },
      { id: 'login', label: 'Sign in with email', action: () => run(async () => { const email = await app.input('Email'); if (email === null) return; const password = await app.input('Password', '', true); if (password === null) return; if (!email.trim() || !password) throw new Error('Email and password are required.'); await app.flixtor.login(email, password); app.refreshAccount(); app.notify('Signed in.'); await app.resumePending(); }) },
      { id: 'logout', label: 'Sign out', action: () => setConfirm({ label: 'Sign out of the shared service account?', action: async () => { await app.flixtor.clear(); app.queries.clear(); app.refreshAccount(); } }) },
    ];
    if (s === 'profiles') return [
      { id: 'switch', label: 'Switch profile', action: () => { app.setProfileId(''); navigate('/'); } },
      { id: 'name', label: 'Profile name', value: p().name, action: () => run(() => input('Profile name', p().name, value => app.repo.saveProfile(p().id, value, p().avatar))) },
      { id: 'avatar', label: 'Avatar', value: String(p().avatar + 1), action: () => navigate('/settings/avatar') },
      { id: 'pin', label: p().locked ? 'Change PIN' : 'Set PIN', action: () => run(async () => {
        const id = p().id;
        const pin = await app.requestInput({ label: 'Choose a four-digit PIN', value: '', mode: 'pin' }); if (pin === null) return;
        const repeated = await app.requestInput({ label: 'Confirm your PIN', value: '', mode: 'pin', validate: value => value === pin ? undefined : 'PINs do not match. Try again.' });
        if (repeated !== null) { await app.repo.setPin(id, repeated); app.notify('PIN saved.'); }
      }) },
      ...(p().locked ? [{ id: 'remove-pin', label: 'Remove PIN', action: () => setConfirm({ label: 'Remove this profile’s PIN?', action: () => app.repo.setPin(p().id, '') }) }] : []),
      { id: 'add', label: 'Add profile', disabled: app.data.profiles.length >= 7, action: () => run(() => input('Profile name', '', value => app.repo.saveProfile(undefined, value, app.data.profiles.length % 7))) },
      { id: 'delete', label: 'Delete current profile', disabled: app.data.profiles.length === 1, action: () => setConfirm({ label: `Delete ${p().name} and their library?`, action: async () => { await app.repo.deleteProfile(p().id); app.setProfileId(''); navigate('/'); } }) },
    ];
    if (s === 'avatar') return Array.from({ length: 7 }, (_, avatar) => ({ id: String(avatar), label: `Avatar ${avatar + 1}`, selected: p().avatar === avatar, action: () => run(async () => { await app.repo.saveProfile(p().id, p().name, avatar); navigate('/settings/profiles'); }) }));
    if (s === 'playback') return [
      { id: 'quality', label: 'Maximum video quality', value: p().quality ? `${p().quality}p` : 'Auto', action: () => navigate('/settings/quality') },
      { id: 'autoplay', label: 'Autoplay next episode', value: p().autoplay ? 'On' : 'Off', action: () => run(() => app.repo.updateProfile(p().id, { autoplay: !p().autoplay })) },
      { id: 'buffer', label: 'Larger playback buffer', value: p().largerBuffer ? 'On' : 'Off', action: () => run(() => app.repo.updateProfile(p().id, { largerBuffer: !p().largerBuffer })) },
    ];
    if (s === 'quality') return [0, 720, 1080, 2160].map(quality => ({ id: String(quality), label: quality ? `${quality}p maximum` : 'Auto', selected: p().quality === quality, action: () => run(async () => { await app.repo.updateProfile(p().id, { quality }); navigate('/settings/playback'); }) }));
    if (s === 'skip') return ['intro', 'recap', 'outro', 'post_credits', 'mixed-op', 'mixed-ed', ...app.repo.customTypes().map(t => t.id)].map(type => ({ id: type, label: app.repo.customTypes().find(t => t.id === type)?.label || type.replaceAll('_', ' '), value: app.data.skip[`action:${p().id}::${type}`] || 'ASK', action: () => run(() => { const key = `action:${p().id}::${type}`; const list = ['ASK', 'AUTO', 'OFF']; return app.repo.setSkip(key, list[(list.indexOf(app.data.skip[key] || 'ASK') + 1) % list.length]); }) }));
    if (s === 'updates') return [
      { id: 'check', label: 'Check for updates', value: app.updates.state.phase, action: () => run(() => app.updates.check()) },
      { id: 'install', label: 'Install downloaded update', disabled: app.updates.state.phase !== 'ready', action: () => run(async () => { const result = await app.updates.install(); if (!result.permission) app.notify('Allow installation, then select Install again.'); }) },
      { id: 'version', label: `Installed ${app.host.version}`, value: app.host.commit.slice(0, 7), disabled: true, action() {} },
    ];
    if (s === 'display') return [
      { id: 'fit', label: 'Adjust screen fit', value: `${app.data.fit.horizontal}% / ${app.data.fit.vertical}%`, action: () => setFit({ ...app.data.fit }) },
      { id: 'motion', label: 'Reduce motion', value: app.data.skip['display:motion'] === 'reduced' ? 'On' : 'Use device preference', action: () => run(() => app.repo.setSkip('display:motion', app.data.skip['display:motion'] === 'reduced' ? undefined : 'reduced')) },
      { id: 'transparency', label: 'Reduce transparency', value: app.data.skip['display:transparency'] === 'reduced' ? 'On' : 'Off', action: () => run(() => app.repo.setSkip('display:transparency', app.data.skip['display:transparency'] === 'reduced' ? undefined : 'reduced')) },
      { id: 'reset', label: 'Reset screen fit', action: () => run(() => app.repo.change(d => { d.fit = { horizontal: 5, vertical: 5 }; })) },
    ];
    if (s === 'horizontal' || s === 'vertical') return Array.from({ length: 11 }, (_, value) => ({ id: String(value), label: `${value}%`, selected: app.data.fit[s] === value, action: () => run(async () => { await app.repo.change(d => { d.fit[s] = value; }); navigate('/settings/display'); }) }));
    if (s === 'library') return [{ id: 'clear', label: 'Clear current profile’s list and viewing history', action: () => setConfirm({ label: 'Clear this profile’s library?', action: () => app.repo.clearLibrary(p().id) }) }];
    return [];
  };
  const categories = ['Account', 'Profiles', 'Playback', 'Skip segments', 'Updates', 'Display', 'Library'];
  return <>
    <Column width={1728} height={848} gap={28} scroll="none">
      <Row width={1728} height={116} gap={26}>
        <Avatar index={p()?.avatar || 0} size={90} />
        <Column width={1300} height={116} gap={12}><text skipFocus width={1300} height={58} fontSize={48} fontFamily="Roboto700">Settings</text>
          <text skipFocus width={1300} height={38} fontSize={26} color={palette.muted}>{p()?.name} · {accountLabel()}</text></Column>
      </Row>
      <Row width={1728} height={680} gap={50}>
        <Column width={330} height={680} gap={14} scroll="none">
          <For each={categories}>{label => <Button label={label} width={330} height={68} quiet selected={section() === label.toLowerCase().replace(' segments', '') || !section() && label === 'Account'} action={() => navigate('/settings/' + label.toLowerCase().replace(' segments', ''))} />}</For>
        </Column>
        <view width={1348} height={680}><Glass width={1348} height={680} radius={30} />
          <view x={30} y={30} width={1288} height={620}>
            <Show when={section() || 'account'} keyed>{current => <Menu width={1288} height={620} title={current === 'skip' ? 'Skip segments' : String(current).charAt(0).toUpperCase() + String(current).slice(1)} options={section() ? options() : [
              { id: 'account', label: accountLabel(), action: () => navigate('/settings/account') },
              { id: 'pair', label: 'Sign in with phone', action: () => navigate('/pair') },
              { id: 'refresh', label: 'Refresh account status', action: () => app.refreshAccount() },
            ]} back={() => navigate(section() ? '/settings' : '/home')} />}</Show>
          </view>
        </view>
      </Row>
    </Column>
    <Show when={confirm()} keyed>{confirmation => <Overlay title={confirmation.label} close={() => { if (!working()) setConfirm(undefined); }} width={980} height={530}>
      <Column width={900} height={458} gap={26} scroll="none"><text skipFocus width={900} height={130} fontSize={38} maxLines={3} fontFamily="Roboto700">{confirmation.label}</text>
        <text skipFocus width={900} height={60} fontSize={25} color={palette.danger}>{confirmError()}</text>
        <Row width={900} height={68} gap={20}><Button label="Cancel" autofocus primary disabled={working()} action={() => { setConfirmError(''); setConfirm(undefined); }} />
          <Button label={working() ? 'Saving…' : 'Confirm'} disabled={working()} action={async () => { setWorking(true); setConfirmError(''); try { await confirmation.action(); setConfirm(undefined); } catch { setConfirmError('Action failed. Try again.'); } finally { setWorking(false); } }} /></Row>
      </Column>
    </Overlay>}</Show>
    <Show when={fit()}><Overlay title="Screen fit" close={() => setFit(undefined)} width={1160} height={900}>
      <Column width={1080} height={828} gap={24} scroll="none"><text skipFocus width={1080} height={58} fontSize={42} fontFamily="Roboto700">Screen fit</text>
        <view skipFocus width={1080} height={360} color={palette.ground} borderRadius={20}>
          <view x={1080 * fit()!.horizontal / 100} y={360 * fit()!.vertical / 100} width={1080 * (1 - fit()!.horizontal / 50)} height={360 * (1 - fit()!.vertical / 50)} border={{ width: 3, color: palette.accent }} borderRadius={8} />
          <text skipFocus x={100} y={145} width={880} height={70} fontSize={30} textAlign="center">Keep the entire frame visible on your TV.</text>
        </view>
        <For each={['horizontal', 'vertical'] as const}>{axis => <Row width={1080} height={68} gap={20}>
          <text skipFocus width={470} height={56} fontSize={28}>{`${axis === 'horizontal' ? 'Horizontal' : 'Vertical'} safe margin · ${fit()![axis]}%`}</text>
          <Button label={`Decrease ${axis}`} width={260} disabled={fit()![axis] <= 0} action={() => setFit(v => ({ ...v!, [axis]: v![axis] - 1 }))} />
          <Button label={`Increase ${axis}`} width={260} disabled={fit()![axis] >= 10} action={() => setFit(v => ({ ...v!, [axis]: v![axis] + 1 }))} />
        </Row>}</For>
        <Row width={1080} height={68} gap={20}><Button label="Save screen fit" primary autofocus action={() => run(async () => { const next = fit()!; await app.repo.change(d => { d.fit = next; }); setFit(undefined); })} /><Button label="Cancel" action={() => setFit(undefined)} /></Row>
      </Column>
    </Overlay></Show>
  </>;
}
