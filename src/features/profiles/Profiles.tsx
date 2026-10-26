import { createSignal, For, Show } from 'solid-js';
import type { ElementNode } from '@solidtv/solid';
import { Column, Row } from '@solidtv/solid/primitives';
import { useNavigate } from '@solidjs/router';
import { useApp } from '../../app/context';
import { Avatar, Button, palette, motion } from '../../ui/components';
import { registerFocus, semantic } from '../../ui/focus';
import { reducedMotion } from '../../ui/theme';
import type { Profile } from '../../domain/models';
import { narratePage } from '../../ui/narration';

export function Profiles() {
  const app = useApp(), navigate = useNavigate();
  const [manage, setManage] = createSignal(false);
  narratePage(() => 'Who’s watching?', () => manage() ? 'Choose a profile to manage.' : 'Your list. Your place in every story.');
  async function choose(profile: Profile) {
    if (profile.locked) {
      const pin = await app.requestInput({ label: `Unlock ${profile.name}`, value: '', mode: 'pin', autoSubmit: true,
        validate: async value => await app.platform.call<boolean>('credentials.verify', { profile: profile.id, pin: value }) ? undefined : 'Incorrect PIN. Try again.' });
      if (pin === null) return;
    }
    app.lastProfile = profile.id; app.setProfileId(profile.id);
    void app.repo.setSkip('lastProfile', profile.id);
    navigate(manage() ? '/settings/profiles' : '/home', { replace: true });
  }
  const scale = () => Math.min((1920 * (1 - 2 * app.data.fit.horizontal / 100)) / 1728, (1080 * (1 - 2 * app.data.fit.vertical / 100)) / 972);
  return <Column x={1920 * app.data.fit.horizontal / 100} y={1080 * app.data.fit.vertical / 100} width={1728} height={972} scale={scale()} pivot={0} gap={38} scroll="none">
    <text skipFocus width={1728} height={90} fontSize={34} fontFamily="Roboto700">FLIXTOR</text>
    <view skipFocus width={1728} height={210}>
      <text skipFocus y={20} width={1728} height={95} fontSize={72} textAlign="center" fontFamily="Roboto700">Who’s watching?</text>
      <text skipFocus y={128} width={1728} height={50} fontSize={29} textAlign="center" color={palette.muted}>{manage() ? 'Choose a profile to manage.' : 'Your list. Your place in every story.'}</text>
    </view>
    <Row width={1728} height={290} gap={28} justifyContent="center" scroll="edge">
      <For each={app.data.profiles}>{(profile, index) => <ProfileTile profile={profile} autofocus={profile.id === (app.lastProfile || app.data.skip.lastProfile || app.data.profiles[0]?.id)} choose={() => app.run(() => choose(profile))} />}</For>
    </Row>
    <Row width={1728} height={68} gap={20} justifyContent="center">
      <Button label={manage() ? 'Done managing' : 'Manage profiles'} icon="settings" width={310} quiet action={() => setManage(!manage())} />
      <Button label="Add profile" icon="plus" width={260} disabled={app.data.profiles.length >= 7} action={() => app.run(async () => {
        const name = await app.requestInput({ label: 'Profile name', value: '', mode: 'text', maxLength: 30, validate: value => !value.trim() ? 'Name is required.' : undefined });
        if (name !== null) await app.repo.saveProfile(undefined, name, app.data.profiles.length % 7);
      })} />
    </Row>
  </Column>;
}
function ProfileTile(props: { profile: Profile; autofocus: boolean; choose(): void }) {
  let node: ElementNode | undefined; const [focused, setFocused] = createSignal(false);
  const off = semantic(() => node, () => props.profile.name, props.choose);
  registerFocus(() => `profile-${props.profile.id}`, () => node, () => props.autofocus);
  return <view ref={node} width={210} height={280} skipFocus={off()} onEnter={() => { if (!off()) props.choose(); return true; }}
    scale={reducedMotion() ? 1 : focused() ? 1.06 : 1} transition={{ scale: motion.lift }} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}>
    <view x={15} y={6} width={180} height={180} color={focused() ? palette.white : 0x00000000} borderRadius={52}>
      <view x={5} y={5} width={170} height={170}><Avatar index={props.profile.avatar} size={170} locked={props.profile.locked} /></view>
    </view>
    <text skipFocus y={211} width={210} height={42} fontSize={29} maxLines={1} textAlign="center" color={focused() ? palette.white : palette.muted}>{props.profile.name}</text>
    <Show when={props.profile.locked}><text skipFocus y={253} width={210} height={28} fontSize={20} textAlign="center" color={palette.muted}>PIN protected</text></Show>
  </view>;
}
