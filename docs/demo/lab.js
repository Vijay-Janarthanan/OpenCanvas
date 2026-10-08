// The live lab. Three ways to watch OpenCanvas work, one engine:
//
//   songs   real songs and their official videos from YouTube, kept on the song by the compiled engine
//   sample  a song and a music video measured in this page, from their headers only
//   files   the same, with the visitor's own two files
//
// `measure` and `WebCanvasPolicy` are the Kotlin classes the Android and desktop apps run, compiled
// to JavaScript (docs/engine/opencanvas-engine.mjs). The page only supplies bytes and drives players.
import { measure, WebCanvasPolicy } from '../engine/opencanvas-engine.mjs';

const SAMPLE = {
  songUrl: 'demo/sample/sample-song.m4a',
  videoUrl: 'demo/sample/sample-video.mp4',
  built: [5000, -5000], // how tools/sample-media cut the video: video time minus song time, per stretch
};
const CHECK_MS = 250;
const YT_TOLERANCE_MS = 300; // an embedded player cannot change speed in fine steps: seek instead
const YT_SEEK_COOLDOWN_MS = 1200;
const COLOURS = ['#8b5cf6', '#ec4899', '#f59e0b', '#22d3ee'];

const $ = (id) => document.getElementById(id);
const el = {
  lab: $('lab'),
  video: $('lab-video'),
  audio: $('lab-audio'),
  ytCanvas: $('lab-yt-canvas'),
  ytSongBox: $('lab-yt-song-box'),
  cover: $('lab-cover'),
  coverImg: $('lab-cover-img'),
  coverText: $('lab-cover-text'),
  badge: $('lab-badge'),
  load: $('lab-load'),
  loadLabel: $('lab-load-label'),
  title: $('lab-title'),
  artist: $('lab-artist'),
  play: $('lab-play'),
  playIcon: $('lab-play-icon'),
  scrub: $('lab-scrub'),
  time: $('lab-time'),
  jumps: $('lab-jumps'),
  tabs: [...document.querySelectorAll('[data-lab-tab]')],
  panels: [...document.querySelectorAll('[data-lab-panel]')],
  songList: $('lab-song-list'),
  songNote: $('lab-song-note'),
  linkForm: $('lab-link-form'),
  link: $('lab-link'),
  linkMsg: $('lab-link-msg'),
  songFile: $('lab-song-file'),
  videoFile: $('lab-video-file'),
  measureFiles: $('lab-measure-files'),
  state: $('lab-state'),
  verdict: $('lab-verdict'),
  reads: $('lab-reads'),
  timing: $('lab-timing'),
  found: $('lab-found'),
  ribbon: $('lab-ribbon'),
  liveSong: $('live-song'),
  liveVideo: $('live-video'),
  liveTarget: $('live-target'),
  liveDrift: $('live-drift'),
  liveAction: $('live-action'),
  liveSeeks: $('live-seeks'),
};

const fmt = (ms) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
const sec = (ms) => `${ms >= 0 ? '+' : '−'}${(Math.abs(ms) / 1000).toFixed(1)} s`;
const kb = (bytes) => (bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`);

let driver = null; // how to read and steer the two players of the current mode
let result = null; // { ok, segments, songSeconds, videoSeconds, ... }
let policy = null;
let songs = [];
let objectUrls = [];
let seeks = 0;
let lastSongMs = 0;
let lastTickAt = 0;
let timer = 0;
let token = 0;

// ================================================================ drivers

/** <audio> + <video> elements: the sample and the visitor's files. */
const htmlDriver = {
  kind: 'html',
  songMs: () => el.audio.currentTime * 1000,
  videoMs: () => el.video.currentTime * 1000,
  songPlaying: () => !el.audio.paused && !el.audio.ended,
  videoReady: () => el.video.readyState >= 2,
  videoBusy: () => el.video.seeking,
  videoPlaying: () => !el.video.paused,
  songDurationMs: () => (result ? result.songSeconds * 1000 : 0),
  videoDurationMs: () => (result ? result.videoSeconds * 1000 : 0),
  playSong: () => el.audio.play().catch(() => setBadge('This browser cannot play the song here', 'warn')),
  pauseSong: () => el.audio.pause(),
  seekSong: (ms) => { el.audio.currentTime = ms / 1000; },
  seekVideo: (ms) => { el.video.currentTime = ms / 1000; },
  playVideo: () => el.video.play().catch(() => {}),
  pauseVideo: () => el.video.pause(),
  setRate: (r) => { el.video.playbackRate = r; },
};

/** Two YouTube embedded players: the song's audio upload and the official video, muted. */
const ytDriver = (() => {
  let song = null;
  let canvas = null;
  let songWanted = false;
  let videoWanted = false;
  let busyUntil = 0;
  const state = (p) => (p && p.getPlayerState ? p.getPlayerState() : -1);
  return {
    kind: 'youtube',
    get ready() { return !!(song && canvas); },
    attach(s, c) { song = s; canvas = c; },
    songMs: () => song.getCurrentTime() * 1000,
    videoMs: () => canvas.getCurrentTime() * 1000,
    songPlaying: () => songWanted,
    videoReady: () => state(canvas) !== YT.PlayerState.UNSTARTED,
    videoBusy: () => performance.now() < busyUntil,
    videoPlaying: () => [YT.PlayerState.PLAYING, YT.PlayerState.BUFFERING].includes(state(canvas)),
    songDurationMs: () => song.getDuration() * 1000,
    videoDurationMs: () => canvas.getDuration() * 1000,
    playSong: () => song.playVideo(),
    pauseSong: () => song.pauseVideo(),
    seekSong: (ms) => song.seekTo(ms / 1000, true),
    seekVideo: (ms) => { canvas.seekTo(ms / 1000, true); busyUntil = performance.now() + YT_SEEK_COOLDOWN_MS; },
    playVideo: () => { videoWanted = true; canvas.playVideo(); },
    pauseVideo: () => { videoWanted = false; canvas.pauseVideo(); },
    setRate: () => {}, // embedded players have no fine speed control: drift is corrected by seeking
    onSongState(code) {
      if (code === YT.PlayerState.PLAYING) songWanted = true;
      else if (code === YT.PlayerState.PAUSED || code === YT.PlayerState.ENDED) songWanted = false;
    },
    onCanvasState(code) {
      // a seek makes a cued or paused embedded player play: put it back to what the policy asked for
      if (code === YT.PlayerState.PLAYING && !videoWanted) canvas.pauseVideo();
    },
    cue(songId, videoId) {
      songWanted = false;
      videoWanted = false;
      busyUntil = 0;
      canvas.mute();
      song.cueVideoById(songId);
      canvas.cueVideoById(videoId);
    },
    free(videoId) {
      songWanted = false;
      song.stopVideo();
      canvas.unMute();
      canvas.cueVideoById(videoId);
    },
  };
})();

// ================================================================ the engine in the page

/** A file served over HTTP: the engine asks for byte ranges, so only the headers are fetched. */
async function urlReader(url) {
  const head = await fetch(url, { method: 'HEAD' });
  if (!head.ok) throw new Error(`Could not reach ${url} (${head.status}).`);
  const length = Number(head.headers.get('content-length'));
  return {
    length,
    read: async (from, count) => {
      const end = Math.min(length, from + count) - 1;
      if (end < from) return new ArrayBuffer(0);
      const response = await fetch(url, { headers: { Range: `bytes=${from}-${end}` } });
      const buffer = await response.arrayBuffer();
      // a server that ignores Range sends the whole file: keep the slice that was asked for
      return response.status === 206 ? buffer : buffer.slice(from, end + 1);
    },
  };
}

/** A file from the visitor's disk: nothing leaves the page. */
function fileReader(file) {
  return { length: file.size, read: (from, count) => file.slice(from, from + count).arrayBuffer() };
}

async function measureHere({ songReader, videoReader, songSrc, videoSrc, sample }) {
  const mine = ++token;
  halt();
  driver = htmlDriver;
  showStage('html');
  setState('Reading the two headers…', 'busy');
  el.verdict.textContent = '';
  setEnabled(false);
  try {
    const started = performance.now();
    const parsed = JSON.parse(await measure(await songReader(), await videoReader()));
    if (mine !== token) return;
    parsed.wallMs = Math.round(performance.now() - started);
    result = parsed;
  } catch (error) {
    if (mine !== token) return;
    result = null;
    policy = null;
    showFailure(error);
    return;
  }
  policy = new WebCanvasPolicy(JSON.stringify(result.segments), result.videoSeconds * 1000);
  el.audio.src = songSrc;
  el.video.src = videoSrc;
  el.audio.load();
  el.video.load();
  el.title.textContent = sample ? 'Sample song' : 'Your song';
  el.artist.textContent = sample ? 'made for this page, with a music video of its own' : 'measured on your computer';
  el.coverImg.removeAttribute('src');
  afterMeasured();
  setState(result.ok ? 'Measured here' : 'Measured, but not used', result.ok ? 'ok' : 'warn');
  el.verdict.textContent = result.ok
    ? `This video is the same recording as the song, in ${result.segments.length} stretch${result.segments.length > 1 ? 'es' : ''}. Confidence ${result.confidence.toFixed(2)}.`
    : `Not used: ${result.error ?? 'no stretch of the song matches the video'}.`;
  el.reads.textContent = `Read ${kb(result.songBytesRead)} of the song and ${kb(result.videoBytesRead)} of the video. Nothing was decoded.`;
  el.timing.textContent = `${result.readMs} ms to read, ${result.alignMs} ms to align.`;
  el.found.textContent = sample && result.ok
    ? `The sample video was cut with ${SAMPLE.built.map(sec).join(' and ')}. The engine found ${result.segments.map((s) => sec(s.offsetMs)).join(' and ')}.`
    : result.segments.map((s) => `${fmt(s.songStartMs)}–${fmt(s.songEndMs)} at ${sec(s.offsetMs)}${s.rate !== 1 ? ` (speed ×${s.rate.toFixed(3)})` : ''}`).join(' · ');
  setEnabled(result.ok);
  setBadge(result.ok ? 'Press play' : 'Not used', result.ok ? 'idle' : 'warn');
  showCover(true, result.ok ? '' : 'The still cover stays: this video does not line up with this song.');
}

function showFailure(error) {
  setState('Could not measure', 'warn');
  el.verdict.textContent = (error && error.message) || String(error);
  el.reads.textContent = el.timing.textContent = el.found.textContent = '';
  el.ribbon.innerHTML = '';
  el.jumps.innerHTML = '';
  setBadge('Not measured', 'warn');
  showCover(true, 'Nothing to show until a song and its video are measured.');
}

function afterMeasured() {
  seeks = 0;
  lastSongMs = 0;
  el.scrub.max = String(Math.round(result.songSeconds * 1000));
  el.scrub.value = '0';
  drawRibbon();
  buildJumps();
  clearInterval(timer);
  timer = setInterval(tick, CHECK_MS);
}

// ================================================================ real songs from YouTube

let ytPromise = null;
function loadYouTube() {
  if (ytPromise) return ytPromise;
  ytPromise = new Promise((resolve, reject) => {
    if (window.YT && window.YT.Player) return resolve();
    window.onYouTubeIframeAPIReady = resolve;
    const tag = document.createElement('script');
    tag.src = 'https://www.youtube.com/iframe_api';
    tag.onerror = () => reject(new Error("Could not load YouTube's player."));
    document.head.appendChild(tag);
  });
  return ytPromise;
}

function playerVars() {
  const vars = { controls: 0, disablekb: 1, fs: 0, rel: 0, modestbranding: 1, playsinline: 1, iv_load_policy: 3 };
  if (location.protocol.startsWith('http')) vars.origin = location.origin;
  return vars;
}

async function startYouTube() {
  el.load.disabled = true;
  el.loadLabel.textContent = 'Loading YouTube players…';
  try {
    await loadYouTube();
    await new Promise((resolve) => {
      let waiting = 2;
      const done = () => --waiting === 0 && resolve();
      const song = new YT.Player('lab-yt-song', {
        width: '100%', height: '100%', playerVars: playerVars(),
        events: { onReady: done, onStateChange: (e) => { ytDriver.onSongState(e.data); syncPlayIcon(); tick(true); }, onError: ytError },
      });
      const canvas = new YT.Player('lab-yt-canvas-player', {
        width: '100%', height: '100%', playerVars: playerVars(),
        events: { onReady: () => { canvas.mute(); done(); }, onStateChange: (e) => ytDriver.onCanvasState(e.data), onError: ytError },
      });
      ytDriver.attach(song, canvas);
    });
  } catch (error) {
    el.loadLabel.textContent = 'Could not load YouTube. Check your connection or blocker, then try again.';
    el.load.disabled = false;
    return;
  }
  el.load.hidden = true;
  selectSong(0);
}

function ytError(event) {
  setBadge(`YouTube error ${event.data}: this video cannot be played here`, 'warn');
}

function selectSong(index) {
  if (!ytDriver.ready) return;
  const song = songs[index];
  ++token;
  halt();
  driver = ytDriver;
  showStage('youtube');
  result = {
    ok: true,
    confidence: song.confidence,
    segments: song.segments.map((s) => ({ ...s, ncc: 0 })),
    songSeconds: Math.max(...song.segments.map((s) => s.songEndMs)) / 1000,
    videoSeconds: 0,
  };
  policy = new WebCanvasPolicy(JSON.stringify(result.segments), 0, YT_TOLERANCE_MS, YT_TOLERANCE_MS);
  ytDriver.cue(song.songVideoId, song.musicVideoId);
  el.title.textContent = song.title;
  el.artist.textContent = song.artist;
  el.songNote.textContent = song.note;
  el.coverImg.src = `https://i.ytimg.com/vi/${song.songVideoId}/hqdefault.jpg`;
  [...el.songList.children].forEach((b, i) => b.setAttribute('aria-pressed', String(i === index)));
  setState('Map from the engine', 'ok');
  el.verdict.textContent = `The library measured this exact pair of YouTube files on a device: ${song.segments.length} stretch${song.segments.length > 1 ? 'es' : ''}, confidence ${song.confidence.toFixed(2)}.`;
  el.reads.textContent = 'It read about 0.3 MB of each file’s header; this page cannot do that for YouTube (see below).';
  el.timing.textContent = '';
  el.found.textContent = song.segments.map((s) => `${fmt(s.songStartMs)}–${fmt(s.songEndMs)} at ${sec(s.offsetMs)}`).join(' · ');
  afterMeasured();
  setEnabled(true);
  setBadge('Press play', 'idle');
  showCover(true, '');
  syncPlayIcon();
}

function parseVideoId(text) {
  const value = text.trim();
  if (/^[\w-]{11}$/.test(value)) return value;
  try {
    const url = new URL(value.startsWith('http') ? value : `https://${value}`);
    if (url.hostname === 'youtu.be') return url.pathname.slice(1, 12);
    const v = url.searchParams.get('v');
    if (v && /^[\w-]{11}$/.test(v)) return v;
    const m = url.pathname.match(/\/(?:shorts|embed|live|v)\/([\w-]{11})/);
    if (m) return m[1];
  } catch (_) { /* not a link */ }
  return null;
}

function onLink(event) {
  event.preventDefault();
  if (!ytDriver.ready) { el.linkMsg.textContent = 'Load the demo first (the button on the phone).'; return; }
  const id = parseVideoId(el.link.value);
  if (!id) { el.linkMsg.textContent = 'That does not look like a YouTube link.'; return; }
  const known = songs.findIndex((s) => s.musicVideoId === id || s.songVideoId === id);
  if (known >= 0) {
    selectSong(known);
    el.linkMsg.textContent = `The engine has a map for “${songs[known].title}”: playing it locked to the song.`;
    return;
  }
  ++token;
  halt();
  driver = ytDriver;
  showStage('youtube');
  result = null;
  policy = null;
  ytDriver.free(id);
  el.title.textContent = 'Your video';
  el.artist.textContent = 'plays on its own, with its own sound';
  el.coverImg.src = `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
  setState('Not measured', 'warn');
  el.verdict.textContent = 'No map for this video yet, so it is only shown as a vertical canvas.';
  el.reads.textContent = el.timing.textContent = el.found.textContent = '';
  el.ribbon.innerHTML = '';
  el.jumps.innerHTML = '';
  setBadge('Free play · not measured', 'idle');
  showCover(false);
  setEnabled(false);
  el.linkMsg.textContent = 'A browser page cannot read YouTube’s media, so it cannot measure this one. The library does that on Android and the desktop, or in a page through a small relay (see below).';
  [...el.songList.children].forEach((b) => b.setAttribute('aria-pressed', 'false'));
}

// ================================================================ the ribbon: the map, drawn

const X0 = 24;
const X1 = 976;

function drawRibbon() {
  if (!result) return;
  const S = result.songSeconds * 1000;
  const V = (driver.videoDurationMs() || result.videoSeconds * 1000 || S);
  const xs = (ms) => X0 + (Math.max(0, Math.min(S, ms)) / S) * (X1 - X0);
  const xv = (ms) => X0 + (Math.max(0, Math.min(V, ms)) / V) * (X1 - X0);
  const parts = [];
  const bar = (y, label) => `<rect x="${X0}" y="${y}" width="${X1 - X0}" height="14" rx="7" fill="rgba(255,255,255,.08)"/><text x="${X0}" y="${y === 38 ? 24 : 156 + 34}" class="rb-label">${label}</text>`;
  parts.push(bar(38, 'song'), bar(166, 'video'));
  for (let t = 0; t <= S; t += 10000) parts.push(`<text x="${xs(t)}" y="68" class="rb-tick" text-anchor="middle">${fmt(t)}</text>`);
  for (let t = 0; t <= V; t += 10000) parts.push(`<text x="${xv(t)}" y="150" class="rb-tick" text-anchor="middle">${fmt(t)}</text>`);
  result.segments.forEach((s, i) => {
    const c = COLOURS[i % COLOURS.length];
    const len = s.songEndMs - s.songStartMs;
    const a = xs(s.songStartMs), b = xs(s.songEndMs);
    const c0 = xv(s.songStartMs + s.offsetMs), c1 = xv(s.songEndMs + s.offsetMs + ((s.rate ?? 1) - 1) * len);
    parts.push(
      `<path d="M${a},52 C${a},106 ${c0},112 ${c0},166 L${c1},166 C${c1},112 ${b},106 ${b},52 Z" fill="${c}" fill-opacity=".34" stroke="${c}" stroke-opacity=".8"/>`,
      `<rect x="${a}" y="38" width="${Math.max(1, b - a)}" height="14" rx="7" fill="${c}"/>`,
      `<rect x="${c0}" y="166" width="${Math.max(1, c1 - c0)}" height="14" rx="7" fill="${c}"/>`,
      `<text x="${(a + b) / 2}" y="112" class="rb-offset" text-anchor="middle">${sec(s.offsetMs)}</text>`,
    );
  });
  parts.push(
    '<path id="rb-link" d="" fill="none" stroke="#fff" stroke-width="1.5" stroke-dasharray="3 3"/>',
    '<rect id="rb-song" x="0" y="30" width="3" height="30" fill="#fff"/>',
    '<rect id="rb-video" x="0" y="158" width="3" height="30" fill="#fff"/>',
  );
  el.ribbon.innerHTML = parts.join('');
  el.ribbon.dataset.video = String(Math.round(V));
}

function updateRibbon(songMs, videoMs, visible) {
  const S = result.songSeconds * 1000;
  const V = Number(el.ribbon.dataset.video) || S;
  const a = X0 + (Math.min(S, songMs) / S) * (X1 - X0);
  const b = X0 + (Math.max(0, Math.min(V, videoMs)) / V) * (X1 - X0);
  $('rb-song')?.setAttribute('x', a - 1.5);
  $('rb-video')?.setAttribute('x', b - 1.5);
  $('rb-link')?.setAttribute('d', visible ? `M${a},52 C${a},106 ${b},112 ${b},166` : '');
}

function buildJumps() {
  el.jumps.innerHTML = '';
  const add = (label, ms) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'lab-chip';
    b.textContent = label;
    b.addEventListener('click', () => seekSong(ms));
    el.jumps.appendChild(b);
  };
  add('From the start', 0);
  result.segments.slice(1).forEach((s, i) => {
    if (s.offsetMs !== result.segments[i].offsetMs) add(`Just before the edit at ${fmt(s.songStartMs)}`, Math.max(0, s.songStartMs - 2500));
  });
}

// ================================================================ keeping the video on the song

function tick(force = false) {
  if (!driver || !result || !policy) return;
  if (driver.kind === 'youtube' && !ytDriver.ready) return;
  const now = performance.now();
  const songMs = driver.songMs();
  const videoMs = driver.videoMs();
  const playing = driver.songPlaying();

  if (driver.kind === 'youtube') learnDurations();
  const total = result.songSeconds * 1000;
  if (document.activeElement !== el.scrub) el.scrub.value = String(Math.round(songMs));
  el.time.textContent = `${fmt(songMs)} / ${fmt(total)}`;

  const jumped = policy.seekDetected(lastSongMs, songMs, now - lastTickAt, playing);
  lastSongMs = songMs;
  lastTickAt = now;
  const covered = policy.isVisible(songMs);
  updateRibbon(songMs, videoMs, covered);
  if (driver.videoBusy() && !jumped && !force) return;

  const action = JSON.parse(policy.decide(songMs, videoMs, playing, driver.videoReady()));
  apply(action, playing);
  const target = policy.targetVideoMs(songMs);
  el.liveSong.textContent = fmt(songMs);
  el.liveVideo.textContent = fmt(videoMs);
  el.liveTarget.textContent = covered ? fmt(target) : '—';
  el.liveDrift.textContent = covered ? `${videoMs - target >= 0 ? '+' : '−'}${Math.abs(Math.round(videoMs - target))} ms` : '—';
  el.liveAction.textContent = {
    hidden: 'still cover', ended: 'ended', hold: 'hold',
    seek: `seek to ${fmt(action.videoMs ?? 0)}`,
    nudge: action.speed === 1 ? 'in sync' : `nudge ×${(action.speed ?? 1).toFixed(3)}`,
  }[action.kind];
  el.liveSeeks.textContent = String(seeks);
}

/** The players report their lengths only once the media is loaded: refresh the map and the ribbon then. */
function learnDurations() {
  const video = ytDriver.videoDurationMs();
  const song = ytDriver.songDurationMs();
  const mapEnd = Math.max(...result.segments.map((s) => s.songEndMs));
  let changed = false;
  if (video > 0 && Math.abs(result.videoSeconds * 1000 - video) > 500) {
    result.videoSeconds = video / 1000;
    policy = new WebCanvasPolicy(JSON.stringify(result.segments), video, YT_TOLERANCE_MS, YT_TOLERANCE_MS);
    changed = true;
  }
  // right after a new song is cued the player may still report the previous song's length
  if (song >= mapEnd - 3000 && Math.abs(result.songSeconds * 1000 - song) > 500) {
    result.songSeconds = song / 1000;
    el.scrub.max = String(Math.round(song));
    changed = true;
  }
  if (changed) drawRibbon();
}

function apply(action, playing) {
  switch (action.kind) {
    case 'hidden':
    case 'ended':
      if (driver.videoPlaying()) driver.pauseVideo();
      showCover(true, action.kind === 'ended' ? 'The video is over. The song goes on: the still cover stays, the video never loops.' : 'The video has no scene for this stretch of the song: the still cover shows.');
      setBadge(action.kind === 'ended' ? 'Video ended' : 'No matching picture', 'warn');
      break;
    case 'seek':
      driver.seekVideo(action.videoMs);
      seeks += 1;
      showCover(false);
      setBadge('Jumping to the song’s position', 'sync');
      if (playing) driver.playVideo();
      break;
    case 'nudge':
      showCover(false);
      driver.setRate(action.speed);
      setBadge('In sync with the song', 'ok');
      if (playing && !driver.videoPlaying()) driver.playVideo();
      break;
    default: // hold
      showCover(false);
      if (!playing && driver.videoPlaying()) driver.pauseVideo();
      setBadge(playing ? 'In sync with the song' : 'Paused on the song’s position', playing ? 'ok' : 'idle');
  }
}

function seekSong(ms) {
  driver.seekSong(ms);
  lastSongMs = ms;
  tick(true);
}

function halt() {
  clearInterval(timer);
  try { driver?.pauseSong(); driver?.pauseVideo(); } catch (_) { /* a player that is not ready */ }
}

// ================================================================ small pieces of UI

function showStage(kind) {
  el.load.hidden = kind !== 'youtube' || ytDriver.ready;
  el.video.hidden = kind !== 'html';
  el.ytCanvas.hidden = kind !== 'youtube';
  el.ytSongBox.hidden = kind !== 'youtube';
}

const BADGE = { idle: 'lab-badge--idle', ok: 'lab-badge--ok', sync: 'lab-badge--sync', warn: 'lab-badge--warn' };

function setBadge(text, kind) {
  el.badge.textContent = text;
  el.badge.className = `lab-badge ${BADGE[kind]}`;
}

function showCover(visible, text) {
  el.cover.dataset.visible = String(visible);
  if (text !== undefined) el.coverText.textContent = text;
}

function setState(text, kind) {
  el.state.textContent = text;
  el.state.dataset.kind = kind;
}

function setEnabled(on) {
  el.play.disabled = !on;
  el.scrub.disabled = !on;
  el.jumps.querySelectorAll('button').forEach((b) => (b.disabled = !on));
}

function syncPlayIcon() {
  const playing = driver && driver.songPlaying && driver.songPlaying();
  el.playIcon.textContent = playing ? '❚❚' : '▶';
  el.play.setAttribute('aria-label', playing ? 'Pause' : 'Play');
}

function revokeUrls() {
  objectUrls.forEach((u) => URL.revokeObjectURL(u));
  objectUrls = [];
}

function selectTab(name) {
  el.tabs.forEach((t) => t.setAttribute('aria-selected', String(t.dataset.labTab === name)));
  el.panels.forEach((p) => (p.hidden = p.dataset.labPanel !== name));
}

function measureSample() {
  revokeUrls();
  return measureHere({ songReader: () => urlReader(SAMPLE.songUrl), videoReader: () => urlReader(SAMPLE.videoUrl), songSrc: SAMPLE.songUrl, videoSrc: SAMPLE.videoUrl, sample: true });
}

function measureFiles() {
  const song = el.songFile.files[0];
  const video = el.videoFile.files[0];
  if (!song || !video) {
    setState('Choose both files', 'warn');
    el.verdict.textContent = 'Pick the song (.m4a or .mp4 with AAC audio) and its music video (.mp4 with AAC audio).';
    return;
  }
  revokeUrls();
  objectUrls = [URL.createObjectURL(song), URL.createObjectURL(video)];
  return measureHere({ songReader: async () => fileReader(song), videoReader: async () => fileReader(video), songSrc: objectUrls[0], videoSrc: objectUrls[1], sample: false });
}

function onTab(name) {
  selectTab(name);
  if (name === 'sample') {
    measureSample();
  } else if (name === 'songs') {
    if (ytDriver.ready) selectSong(Math.max(0, [...el.songList.children].findIndex((b) => b.getAttribute('aria-pressed') === 'true')));
    else idleSongs();
  } else {
    ++token;
    halt();
    result = null;
    policy = null;
    setEnabled(false);
    setState('Waiting for your files', 'idle');
    el.verdict.textContent = 'Choose a song and its music video, then measure. They stay on your computer.';
    el.reads.textContent = el.timing.textContent = el.found.textContent = '';
    el.ribbon.innerHTML = '';
    el.jumps.innerHTML = '';
    showStage('html');
    setBadge('Choose two files', 'idle');
    showCover(true, '');
  }
}

function idleSongs() {
  halt();
  setEnabled(false);
  showStage('youtube');
  setBadge('Demo not loaded', 'idle');
  setState('Not loaded', 'idle');
  el.verdict.textContent = 'Load the demo, then pick a song. The map under each song was measured by the library.';
  el.reads.textContent = el.timing.textContent = el.found.textContent = '';
  el.ribbon.innerHTML = '';
  el.jumps.innerHTML = '';
  showCover(true, '');
}

function init() {
  el.video.muted = true;
  el.tabs.forEach((t) => t.addEventListener('click', () => onTab(t.dataset.labTab)));
  el.load.addEventListener('click', startYouTube);
  el.measureFiles.addEventListener('click', measureFiles);
  el.linkForm.addEventListener('submit', onLink);
  el.play.addEventListener('click', () => (driver.songPlaying() ? driver.pauseSong() : driver.playSong()));
  const onAudio = () => { syncPlayIcon(); tick(true); };
  ['play', 'pause', 'ended', 'seeked'].forEach((e) => el.audio.addEventListener(e, onAudio));
  el.video.addEventListener('seeked', () => tick());
  el.scrub.addEventListener('input', () => seekSong(Number(el.scrub.value)));
  document.addEventListener('visibilitychange', () => { if (document.hidden) { try { driver?.pauseSong(); } catch (_) { /* not ready */ } } });

  fetch('demo/songs.json').then((r) => r.json()).then((data) => {
    songs = data.songs;
    el.songList.innerHTML = '';
    songs.forEach((song, i) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'lab-song';
      b.setAttribute('aria-pressed', 'false');
      b.innerHTML = '<span class="lab-song-title"></span><span class="lab-song-artist"></span>';
      b.children[0].textContent = song.title;
      b.children[1].textContent = `${song.artist} · ${song.segments.length > 1 ? `${song.segments.length} stretches` : `${sec(song.segments[0].offsetMs)}`}`;
      b.addEventListener('click', () => (ytDriver.ready ? selectSong(i) : (el.linkMsg.textContent = 'Load the demo first (the button on the phone).')));
      el.songList.appendChild(b);
    });
    el.title.textContent = songs[0].title;
    el.artist.textContent = songs[0].artist;
    el.songNote.textContent = songs[0].note;
    el.coverImg.src = `https://i.ytimg.com/vi/${songs[0].songVideoId}/hqdefault.jpg`;
  }).catch(() => { el.loadLabel.textContent = 'Could not read the song list.'; });

  selectTab('songs');
  idleSongs();
}

init();
