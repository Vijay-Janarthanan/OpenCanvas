import { WebSubjectCamera } from '../engine/opencanvas-engine.mjs';

const section = document.querySelector('#smart-framing');
if (section) {
  const before = section.querySelector('#rf-before');
  const after = section.querySelector('#rf-after');
  const scene = section.querySelector('#rf-scene');
  const position = section.querySelector('#rf-position');
  const play = section.querySelector('#rf-play');
  const status = section.querySelector('#rf-state');
  const source = document.createElement('canvas');
  source.width = 1280; source.height = 720;
  let camera = new WebSubjectCamera(16 / 9, 4 / 5);
  let playing = false, time = 0, last = 0, analyzed = -1;
  let frame = {cx: .5, cy: .5, fitFullFrame: true, sceneCut: true};
  let displayX = .5;
  function actor(context, x, color) {
    const cx = x * source.width;
    context.fillStyle = color;
    context.beginPath(); context.ellipse(cx, 205, 35, 42, 0, 0, Math.PI * 2); context.fill();
    context.fillRect(cx - 44, 253, 88, 208);
    context.fillRect(cx - 40, 456, 28, 168); context.fillRect(cx + 12, 456, 28, 168);
    context.fillRect(cx - 68, 268, 25, 190); context.fillRect(cx + 43, 268, 25, 190);
  }
  function subjects() {
    if (scene.value === 'landscape') return {faces: [], people: []};
    const centers = scene.value === 'duet' ? [.18, .82] : [Number(position.value) / 100];
    return {faces: centers.map(x => [x-.028, .22, x+.028, .35, .98]),
      people: centers.map(x => [x-.055, .22, x+.055, .87, .96])};
  }
  function paint(cut = false) {
    const boxes = subjects();
    const ctx = source.getContext('2d');
    ctx.fillStyle = '#1c2540'; ctx.fillRect(0, 0, 1280, 720);
    ctx.fillStyle = '#303c54';
    for(let x=0;x<1280;x+=160) ctx.fillRect(x+30, 50, 2, 560);
    ctx.fillStyle = '#111827'; ctx.fillRect(0, 625, 1280, 95);
    boxes.people.forEach((b,i) => actor(ctx, (b[0]+b[2])/2, i ? '#ecb28c' : '#c4b5fd'));
    if (cut || time - analyzed >= .25 || analyzed < 0) {
      frame = JSON.parse(camera.update(JSON.stringify(boxes), time, cut)); analyzed = time;
      status.textContent = frame.fitFullFrame ? 'Full frame · keep the group or scene visible' : 'Subject crop · follow within this shot';
    }
    displayX = cut || frame.sceneCut ? frame.cx : displayX + (frame.cx-displayX)*.18;
    for(const [canvas, smart] of [[before,false],[after,true]]) {
      const c=canvas.getContext('2d'); c.fillStyle='#05070d'; c.fillRect(0,0,canvas.width,canvas.height);
      const contain = smart && frame.fitFullFrame;
      const scale = contain ? Math.min(canvas.width/1280,canvas.height/720) : Math.max(canvas.width/1280,canvas.height/720);
      const width=1280*scale, height=720*scale;
      const left=contain ? (canvas.width-width)/2 : Math.max(canvas.width-width,Math.min(0,canvas.width/2-(smart?displayX:.5)*width));
      c.drawImage(source,left,(canvas.height-height)/2,width,height);
    }
  }
  function loop(now) {
    if (last) time += Math.min(.05,(now-last)/1000); last=now;
    if (playing) {position.value=String(50+Math.sin(time*.6)*32);paint();}
    requestAnimationFrame(loop);
  }
  scene.addEventListener('change',()=>{camera=new WebSubjectCamera(16/9,4/5);position.disabled=scene.value!=='solo';paint(true);});
  position.addEventListener('input',()=>paint(true));
  play.addEventListener('click',()=>{playing=!playing;play.textContent=playing?'Pause movement':'Play movement';play.setAttribute('aria-pressed',String(playing));});
  document.addEventListener('visibilitychange',()=>{if(document.hidden){playing=false;play.textContent='Play movement';play.setAttribute('aria-pressed','false');}});
  paint(true);requestAnimationFrame(loop);
}
