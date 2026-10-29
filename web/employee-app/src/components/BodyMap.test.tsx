import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { stringsFor } from '../i18n/strings';
import { initialReferralState, referralReducer, toggleCode } from '../intake/referralForm';
import { findAll, findOne } from '../test/tree';
import { REGIONS } from '../test/fixtures';
import { BodyMap, type BodyMapProps } from './BodyMap';
import { parseRegionCode, shapeFor } from './bodyMapGeometry';

const t = stringsFor('en');
const props = (over: Partial<BodyMapProps> = {}): BodyMapProps => ({
  id: 'bm',
  regions: REGIONS,
  selected: [],
  side: 'FRONT',
  onSideChange: vi.fn(),
  onToggle: vi.fn(),
  labels: t,
  ...over,
});

describe('BodyMap – pointer (SVG)', () => {
  it('toggles a region when its shape is clicked', () => {
    const p = props();
    const shape = findOne(BodyMap(p), (e) => (e.type === 'rect' || e.type === 'ellipse') && e.props['data-code'] === 'HAND_R');
    shape.props.onClick();
    expect(p.onToggle).toHaveBeenCalledWith('HAND_R');
  });

  it('marks selected shapes and hides the drawing from assistive technology', () => {
    const html = renderToStaticMarkup(<BodyMap {...props({ selected: ['HAND_R'] })} />);
    expect(html).toMatch(/<svg[^>]*aria-hidden="true"/);
    expect(html).toMatch(/class="region selected"[^>]*data-code="HAND_R"/);
    expect(html).toMatch(/class="region"[^>]*data-code="HAND_L"/);
  });

  it('draws only the regions of the current side', () => {
    const shapes = (side: 'FRONT' | 'BACK') =>
      findAll(BodyMap(props({ side })), (e) => e.type === 'rect' || e.type === 'ellipse').map((e) => e.props['data-code']);
    expect(shapes('FRONT')).toEqual(['HEAD', 'HAND_R', 'HAND_L', 'KNEE_R']); // TONGUE has no shape
    expect(shapes('BACK')).toEqual(['LOWER_BACK']); // HAND_R_BACK: unknown base code → list only
  });

  it('places the person’s right on the viewer’s left in front view and on the right in back view', () => {
    const x = (code: string, side: string) => {
      const s = shapeFor({ code, label: code, side })!;
      return s.kind === 'rect' ? s.x + s.w / 2 : s.cx;
    };
    expect(x('HAND_R', 'FRONT')).toBeLessThan(100);
    expect(x('HAND_L', 'FRONT')).toBeGreaterThan(100);
    expect(x('HAND_R', 'BACK')).toBeGreaterThan(100);
    expect(parseRegionCode('knee_left')).toEqual({ base: 'KNEE', lat: 'L' });
  });
});

describe('BodyMap – keyboard / screen-reader alternative', () => {
  it('lists every region of the side as a native toggle button, including ones without a shape', () => {
    const buttons = findAll(BodyMap(props()), (e) => e.type === 'button' && e.props.className === 'toggle');
    expect(buttons.map((b) => b.props['data-code'])).toEqual(['HEAD', 'HAND_R', 'HAND_L', 'KNEE_R', 'TONGUE']);
    // native <button type="button">: Enter and Space activate it → onClick
    buttons.forEach((b) => expect(b.props.type).toBe('button'));
  });

  it('exposes the selection state via aria-pressed', () => {
    const html = renderToStaticMarkup(<BodyMap {...props({ selected: ['KNEE_R'] })} />);
    expect(html).toMatch(/aria-pressed="true" data-code="KNEE_R"/);
    expect(html).toMatch(/aria-pressed="false" data-code="HEAD"/);
    expect(html).toContain('Right knee (Front)');
  });

  it('toggles on activation and the reducer adds then removes the region', () => {
    const p = props();
    const btn = findOne(BodyMap(p), (e) => e.type === 'button' && e.props['data-code'] === 'TONGUE');
    btn.props.onClick();
    expect(p.onToggle).toHaveBeenCalledWith('TONGUE');

    let s = initialReferralState('en');
    s = referralReducer(s, { type: 'toggleBodyPart', code: 'TONGUE' });
    expect(s.bodyParts).toEqual(['TONGUE']);
    s = referralReducer(s, { type: 'toggleBodyPart', code: 'TONGUE' });
    expect(s.bodyParts).toEqual([]);
    expect(toggleCode(['A', 'B'], 'A')).toEqual(['B']);
  });

  it('switches front/back with pressed-state buttons', () => {
    const p = props();
    const segs = findAll(BodyMap(p), (e) => e.type === 'button' && e.props.className === 'segment');
    expect(segs.map((s) => s.props['aria-pressed'])).toEqual([true, false]);
    segs[1]!.props.onClick();
    expect(p.onSideChange).toHaveBeenCalledWith('BACK');
    const back = findAll(BodyMap(props({ side: 'BACK' })), (e) => e.type === 'button' && e.props.className === 'toggle');
    expect(back.map((b) => b.props['data-code'])).toEqual(['LOWER_BACK', 'HAND_R_BACK']);
  });

  it('shows a required error and marks the group invalid', () => {
    const html = renderToStaticMarkup(<BodyMap {...props({ required: true, error: 'This field is required.' })} />);
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('id="bm-error"');
  });
});
