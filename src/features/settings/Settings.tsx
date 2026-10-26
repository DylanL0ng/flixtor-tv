import { createSignal, Show } from 'solid-js';
import { Column } from '@solidtv/solid/primitives';
import { useLocation, useNavigate } from '@solidjs/router';
import { useApp } from '../../app/context';
import { Menu, palette, type Option } from '../../ui/components';
import { titleKey } from '../../domain/models';

export function Settings() {
  const app = useApp(), location = useLocation(), navigate = useNavigate();
  const [confirm, setConfirm] = createSignal<{ label: string; action(): Promise<unknown> }>();
  const section = () => location.pathname.split('/')[2] || '';
  const p = () => app.profile()!;
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
      { id: 'pin', label: p().locked ? 'Change / remove PIN' : 'Set PIN', action: () => run(() => input('Four-digit PIN (blank removes lock)', '', value => app.repo.setPin(p().id, value), true)) },
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
      { id: 'horizontal', label: 'Horizontal safe margin', value: `${app.data.fit.horizontal}%`, action: () => navigate('/settings/horizontal') },
      { id: 'vertical', label: 'Vertical safe margin', value: `${app.data.fit.vertical}%`, action: () => navigate('/settings/vertical') },
      { id: 'reset', label: 'Reset screen fit', action: () => run(() => app.repo.change(d => { d.fit = { horizontal: 5, vertical: 5 }; })) },
    ];
    if (s === 'horizontal' || s === 'vertical') return Array.from({ length: 11 }, (_, value) => ({ id: String(value), label: `${value}%`, selected: app.data.fit[s] === value, action: () => run(async () => { await app.repo.change(d => { d.fit[s] = value; }); navigate('/settings/display'); }) }));
    if (s === 'library') return [{ id: 'clear', label: 'Clear current profile’s list and viewing history', action: () => setConfirm({ label: 'Clear this profile’s library?', action: () => app.repo.clearLibrary(p().id) }) }];
    return [];
  };
  return <Column width={1400} height={900} gap={30} autofocus>
    <text skipFocus width={1380} height={85} fontSize={60} color={palette.foreground}>Settings{section() ? ` / ${section().replaceAll('-', ' ')}` : ''}</text>
    <text skipFocus width={1380} height={50} fontSize={27} color={palette.muted}>{p()?.name} · One shared service account, separate profile libraries.</text>
    <Show when={!confirm()} fallback={<Menu width={1350} title={confirm()!.label} options={[
      { id: 'cancel', label: 'Cancel', action: () => setConfirm(undefined) },
      { id: 'confirm', label: 'Confirm', action: () => run(async () => { const action = confirm()!.action; setConfirm(undefined); await action(); }) },
    ]} />}>
      <Menu width={1350} options={options()} back={() => navigate(section() ? '/settings' : '/home')} />
    </Show>
  </Column>;
}
