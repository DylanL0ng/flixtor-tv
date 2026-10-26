import { useNavigate } from '@solidjs/router';
import { Column } from '@solidtv/solid/primitives';
import { useApp } from '../../app/context';
import { Button, Menu, motion, palette } from '../../ui/components';
export function Profiles() {
  const app = useApp(), navigate = useNavigate();
  return <Column x={310} y={130} width={1300} height={850} gap={28} autofocus alpha={1} transition={{ alpha: motion.enter }}>
    <text skipFocus width={1250} height={90} fontSize={64} color={palette.accent}>Who’s watching?</text>
    <text skipFocus width={1250} height={50} fontSize={28} color={palette.muted}>Your list. Your place in every story.</text>
    <Menu height={550} width={1250} options={app.data.profiles.map(p => ({ id: p.id, label: p.name, value: p.locked ? 'PIN protected' : '', action: () => app.run(async () => {
      if (p.locked) { const pin = await app.input('Profile PIN', '', true); if (pin === null) return; if (!await app.platform.call<boolean>('credentials.verify', { profile: p.id, pin })) throw new Error('Incorrect PIN. Try again.'); }
      app.setProfileId(p.id); navigate('/home', { replace: true });
    }) }))} />
    <Button label="Add profile" disabled={app.data.profiles.length >= 7} action={() => app.run(async () => { const name = await app.input('Profile name'); if (name !== null) await app.repo.saveProfile(undefined, name, app.data.profiles.length % 7); })} />
  </Column>;
}
