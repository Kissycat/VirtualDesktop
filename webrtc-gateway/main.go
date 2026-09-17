package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"encoding/xml"
	"flag"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

/*
#cgo LDFLAGS: -Wl,-Bstatic -L../../usr/lib -lopus -lfdk-aac -Wl,-Bdynamic -lm
#include <opus/opus.h>
#include <fdk-aac/aacenc_lib.h>
#include <stdlib.h>

typedef struct {
    OpusDecoder *opus_dec;
    HANDLE_AACENCODER aac_enc;
    int channels;
    int sample_rate;
    int frame_samples;
    short *pcm_fifo;
    int pcm_fifo_samples;
    int pcm_fifo_capacity;
} OpusToAACTranscoder;

static OpusToAACTranscoder* init_transcoder(int sample_rate, int channels, int bitrate) {
    OpusToAACTranscoder *t = (OpusToAACTranscoder*)calloc(1, sizeof(OpusToAACTranscoder));
    if (!t) return NULL;

    t->channels = channels;
    t->sample_rate = sample_rate;
    t->frame_samples = 1024 * channels;
    // Keep several AAC frames of interleaved PCM so that each call to the
    // encoder always receives complete 1024-sample/channel blocks.
    t->pcm_fifo_capacity = 12288 * channels;
    t->pcm_fifo = (short*)calloc((size_t)t->pcm_fifo_capacity, sizeof(short));
    if (!t->pcm_fifo) {
        free(t);
        return NULL;
    }

    int err = OPUS_OK;
    t->opus_dec = opus_decoder_create(sample_rate, channels, &err);
    if (err != OPUS_OK || !t->opus_dec) {
        free(t->pcm_fifo);
        free(t);
        return NULL;
    }

    CHANNEL_MODE mode = (channels == 1) ? MODE_1 : MODE_2;
    if (aacEncOpen(&t->aac_enc, 0, channels) != AACENC_OK) {
        opus_decoder_destroy(t->opus_dec);
        free(t->pcm_fifo);
        free(t);
        return NULL;
    }

    if (aacEncoder_SetParam(t->aac_enc, AACENC_AOT, 2) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_SAMPLERATE, sample_rate) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_CHANNELMODE, mode) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_CHANNELORDER, 1) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_BITRATE, bitrate) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_TRANSMUX, 2) != AACENC_OK ||
        aacEncoder_SetParam(t->aac_enc, AACENC_AFTERBURNER, 1) != AACENC_OK) {
        aacEncClose(&t->aac_enc);
        opus_decoder_destroy(t->opus_dec);
        free(t->pcm_fifo);
        free(t);
        return NULL;
    }

    if (aacEncEncode(t->aac_enc, NULL, NULL, NULL, NULL) != AACENC_OK) {
        aacEncClose(&t->aac_enc);
        opus_decoder_destroy(t->opus_dec);
        free(t->pcm_fifo);
        free(t);
        return NULL;
    }

    return t;
}

static int transcode_frame(OpusToAACTranscoder *t,
                           const unsigned char *opus_data, int opus_len,
                           unsigned char *aac_out, int max_aac_out) {
    if (!t || !t->opus_dec || !t->aac_enc || !opus_data || opus_len <= 0 ||
        !aac_out || max_aac_out <= 0) {
        return -1;
    }

    // 120 ms is the maximum Opus frame size accepted by opus_decode().
    short pcm[5760 * 2];
    int decoded_samples = opus_decode(t->opus_dec, opus_data, opus_len, pcm, 5760, 0);
    if (decoded_samples < 0) {
        return -2;
    }

    int decoded_total = decoded_samples * t->channels;
    if (decoded_total <= 0 || decoded_total > t->pcm_fifo_capacity - t->pcm_fifo_samples) {
        return -3;
    }
    memcpy(t->pcm_fifo + t->pcm_fifo_samples, pcm,
           (size_t)decoded_total * sizeof(short));
    t->pcm_fifo_samples += decoded_total;

    int total_out = 0;

    // FDK's input is a stream of interleaved PCM samples. Feed complete AAC
    // frame-sized blocks and retain anything not yet consumable.
    while (t->pcm_fifo_samples >= t->frame_samples && total_out < max_aac_out) {
        void *in_buffer = t->pcm_fifo;
        int in_identifier = IN_AUDIO_DATA;
        int in_size = t->frame_samples * (int)sizeof(short);
        int in_elem_size = (int)sizeof(short);
        AACENC_BufDesc in_buf = {1, &in_buffer, &in_identifier, &in_size, &in_elem_size};
        AACENC_InArgs in_args = {0};
        AACENC_OutArgs out_args = {0};
        in_args.numInSamples = t->frame_samples;

        void *out_buffer = aac_out + total_out;
        int out_identifier = OUT_BITSTREAM_DATA;
        int out_size = max_aac_out - total_out;
        int out_elem_size = 1;
        AACENC_BufDesc out_buf = {1, &out_buffer, &out_identifier, &out_size, &out_elem_size};

        AACENC_ERROR enc_err = aacEncEncode(t->aac_enc, &in_buf, &out_buf, &in_args, &out_args);
        if (enc_err != AACENC_OK && enc_err != AACENC_ENCODE_EOF) {
            return total_out > 0 ? total_out : -4;
        }

        int consumed = out_args.numInSamples;
        if (consumed < 0 || consumed > t->pcm_fifo_samples) {
            return total_out > 0 ? total_out : -5;
        }
        if (consumed > 0) {
            memmove(t->pcm_fifo,
                    t->pcm_fifo + consumed,
                    (size_t)(t->pcm_fifo_samples - consumed) * sizeof(short));
            t->pcm_fifo_samples -= consumed;
        } else {
            // Avoid spinning forever if the encoder cannot consume the block.
            break;
        }

        if (out_args.numOutBytes > 0) {
            total_out += out_args.numOutBytes;
        }

        // A single call should normally consume the complete supplied block.
        // If it consumed only part of it, retry with the retained samples.
        if (consumed < t->frame_samples) {
            break;
        }
    }

    return total_out;
}

static void free_transcoder(OpusToAACTranscoder *t) {
    if (!t) return;
    if (t->opus_dec) opus_decoder_destroy(t->opus_dec);
    if (t->aac_enc) aacEncClose(&t->aac_enc);
    free(t->pcm_fifo);
    free(t);
}
*/
import "C"
import "unsafe"

type AudioTranscoder struct {
	ptr *C.OpusToAACTranscoder
}

func NewAudioTranscoder(sampleRate, channels, bitrate int) *AudioTranscoder {
	ptr := C.init_transcoder(C.int(sampleRate), C.int(channels), C.int(bitrate))
	if ptr == nil {
		return nil
	}
	return &AudioTranscoder{ptr: ptr}
}

func (t *AudioTranscoder) Transcode(opusData []byte) []byte {
	if t == nil || t.ptr == nil || len(opusData) == 0 {
		return nil
	}
	buf := make([]byte, 8192)
	outLen := C.transcode_frame(
		t.ptr,
		(*C.uchar)(unsafe.Pointer(&opusData[0])),
		C.int(len(opusData)),
		(*C.uchar)(unsafe.Pointer(&buf[0])),
		C.int(len(buf)),
	)
	if outLen <= 0 {
		return nil
	}
	return buf[:outLen]
}

func (t *AudioTranscoder) Close() {
	if t != nil && t.ptr != nil {
		C.free_transcoder(t.ptr)
		t.ptr = nil
	}
}

const (
	// Built-in scrcpy daemon roles used by this video-only gateway.
	roleVideo       = 0
	roleAudio       = 1
	roleControl     = 2
	roleNegotiation = 3
	roleInternalSHM = 3 // negotiation-only role declaration; not a TCP role socket

	// Current daemon protocol message.
	typeConfigureSession = 216
	typeGenericResponse  = 100

	// Codec identifiers used by the built-in scrcpy daemon.
	// The reference scrcpy Java project normally uses the ASCII token H264,
	// while this VirtualDisplay build exposes its internal enum/tag as 0x20000000.
	codecH264ID       = 0x68323634
	codecH264LegacyID = 0x20000000

	// Streamer packet flags.
	packetFlagSession  uint32 = 1 << 31
	packetFlagConfig   uint64 = 1 << 62
	packetFlagKeyFrame uint64 = 1 << 61

	shmRingFlagHeader uint32 = 1
	shmRingFlagMeta   uint32 = 1 << 1
	shmRingFlagPacket uint32 = 1 << 2
	shmRingFlagConfig uint32 = 1 << 3
	shmRingFlagKey    uint32 = 1 << 4

	flagConfig   = 1 << 0
	flagKeyFrame = 1 << 1

	defaultH264ProfileLevelID        = "42e032"
	frameHistoryWindow               = 4 * time.Second
	frameHistoryMaxFrames            = 600
	maxFramePayload                  = 32 * 1024 * 1024
	maxAudioPayload                  = 4 * 1024 * 1024
	audioCodecOpusID          uint32 = 0x6f707573

	// DLNA/UPnP defaults. DLNA consumes a live MPEG-TS resource over HTTP;
	// the gateway does not decode or re-encode the scrcpy H.264 stream.
	dlnaDefaultListenHost = ""
	dlnaStreamPath        = "/dlna/stream.ts"
	dlnaControlTimeout    = 4 * time.Second
	dlnaHTTPTimeout       = 8 * time.Second

	tsPIDVideo uint16 = 0x0100
	tsPIDAudio uint16 = 0x0101
	tsPIDPMT   uint16 = 0x1000
	tsPCRBase  uint64 = 90000

	rawFrameDefaultAddr            = "127.0.0.1:27184"
	rawFrameMagic           uint32 = 0x56445257 // VDRW
	rawFrameProtocolVersion uint32 = 1
	rawFrameRawMagic        uint32 = 0x4652414d // FRAM
	rawFrameTileMagic       uint32 = 0x54494c45 // TILE
	rawFrameTileSize               = 256
)

const browserHTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VirtualDisplay WebRTC</title>
<style>
html,body{margin:0;width:100%;height:100%;background:#111;color:#ddd;font-family:system-ui,sans-serif;overflow:hidden}
body{display:flex;flex-direction:column}
#bar{height:38px;display:flex;align-items:center;gap:12px;padding:0 12px;background:#202020;box-sizing:border-box;font-size:13px}
:fullscreen #bar{display:none!important}
:-webkit-full-screen #bar{display:none!important}
#status{color:#9ad}
button{background:#333;color:#ddd;border:1px solid #555;border-radius:4px;padding:5px 10px;cursor:pointer}
#wrap{position:relative;flex:1;min-height:0;overflow:hidden;background:#000}
#video{position:absolute;left:50%;top:50%;display:block;background:#000;object-fit:fill;transform:translate(-50%,-50%);transform-origin:center center}#tileCanvas{position:absolute;left:0;top:0;width:100%;height:100%;display:none;background:#000}
#cursor{position:absolute;left:0;top:0;width:20px;height:24px;pointer-events:none;z-index:20;display:none;transform:translate3d(0,0,0);will-change:transform;filter:drop-shadow(0 1px 2px rgba(0,0,0,.28))}
#cursor svg{display:block;width:20px;height:24px;overflow:visible}
</style>
</head>
<body>
<div id="bar"><span>VirtualDisplay WebRTC</span><span id="status">connecting...</span><label><input id="audioEnabled" type="checkbox" checked>音频</label><button id="soundBtn">🔊声音</button><audio id="audioOut" autoplay playsinline></audio><button id="reconnect">Reconnect</button><button id="fullscreenBtn">全屏</button><a href="/dlna" style="color:#9ad;text-decoration:none">DLNA投屏</a></div>
<div id="wrap"><video id="video" autoplay playsinline muted></video><canvas id="tileCanvas"></canvas><canvas id="tileScratch" style="display:none"></canvas><div id="cursor"><svg viewBox="0 0 20 24" aria-hidden="true"><path d="M2.2 1.5 17.8 16.1 12.3 16.6 16.3 22.3 13.6 23.6 9.7 17.9 5.1 21.1Z" fill="#ffffff" stroke="#111111" stroke-width="0.65" stroke-linejoin="round" stroke-linecap="round"/></svg></div></div>
<script>
const video=document.getElementById('video');
const wrap=document.getElementById('wrap');
const cursor=document.getElementById('cursor');
const status=document.getElementById('status');
const bar=document.getElementById('bar');
const audioEnabled=document.getElementById('audioEnabled');
const soundBtn=document.getElementById('soundBtn');
const audioOut=document.getElementById('audioOut');
const fullscreenBtn=document.getElementById('fullscreenBtn');
const tileCanvas=document.getElementById('tileCanvas');
const tileCtx=tileCanvas.getContext('2d',{alpha:false,desynchronized:true});
const tileScratch=document.getElementById('tileScratch');
const tileScratchCtx=tileScratch.getContext('2d',{alpha:false,desynchronized:true});
let tileChannel=null;
let tilePending=false;
let tileMode=false;
let pc=null;
let expectedW=0, expectedH=0;
let sourceW=0, sourceH=0;
let rotated=false;

// 优化后的状态处理函数
function setStatus(s){
  status.textContent=s;
  console.log('[WebRTC]',s);
  // 当连接成功时隐藏状态栏，其他异常/加载状态则显示
  if((s === 'connected' || s === 'PC: connected') && !audioEnabled.checked) {
    bar.style.display = 'none';
    // 隐藏状态栏后，留出时间让DOM更新，并重新计算视频画面的布局以铺满全屏
    setTimeout(applyVideoLayout, 50); 
  } else {
    bar.style.display = 'flex';
  }
}

function fitRect(aspect,w,h){
  if(!aspect || w<=0 || h<=0) return {w:0,h:0};
  let tw=w, th=Math.round(w/aspect);
  if(th>h){th=h;tw=Math.round(h*aspect);}
  return {w:tw,h:th};
}

function applyVideoLayout(){
  const w=wrap.clientWidth, h=wrap.clientHeight;
  if(w<=0 || h<=0) return;

  const logicalW=expectedW||sourceW||video.videoWidth;
  const logicalH=expectedH||sourceH||video.videoHeight;
  if(logicalW<=0 || logicalH<=0) return;

  const srcW=sourceW||video.videoWidth;
  const srcH=sourceH||video.videoHeight;
  rotated = expectedW>0 && expectedH>0 && srcW>0 && srcH>0 &&
            ((expectedW>expectedH)!=(srcW>srcH));

  const r=fitRect(logicalW/logicalH,w,h);
  if(r.w<=0 || r.h<=0) return;

  if(rotated){
    video.style.width=r.h+'px';
    video.style.height=r.w+'px';
    video.style.transform='translate(-50%,-50%) rotate(90deg)';
    tileCanvas.style.left='50%';
    tileCanvas.style.top='50%';
    tileCanvas.style.width=r.h+'px';
    tileCanvas.style.height=r.w+'px';
    tileCanvas.style.transform='translate(-50%,-50%) rotate(90deg)';
  }else{
    video.style.width=r.w+'px';
    video.style.height=r.h+'px';
    video.style.transform='translate(-50%,-50%)';
    tileCanvas.style.left='50%';
    tileCanvas.style.top='50%';
    tileCanvas.style.width=r.w+'px';
    tileCanvas.style.height=r.h+'px';
    tileCanvas.style.transform='translate(-50%,-50%)';
  }
}

let cursorTarget=null;
let cursorRender=null;
let cursorRenderRAF=0;
let cursorLastRenderTime=0;
function setCursorSample(c){
  if(!c || !c.visible || !c.width || !c.height){
    cursorTarget=null;
    cursorRender=null;
    cursor.style.display='none';
    return;
  }
  cursorTarget={x:c.x,y:c.y,width:c.width,height:c.height,visible:true};
  if(!cursorRender) cursorRender={x:c.x,y:c.y};
  if(!cursorRenderRAF) cursorRenderRAF=requestAnimationFrame(renderCursor);
}
function renderCursor(now){
  cursorRenderRAF=0;
  if(!cursorTarget || !cursorTarget.visible || !video.videoWidth || !video.videoHeight){
    cursor.style.display='none';
    return;
  }
  const vr=video.getBoundingClientRect(), wr=wrap.getBoundingClientRect();
  if(vr.width<=0||vr.height<=0){cursor.style.display='none';return;}
  if(!cursorLastRenderTime) cursorLastRenderTime=now;
  const dt=Math.min(32,Math.max(1,now-cursorLastRenderTime));
  cursorLastRenderTime=now;
  // Critically damped-style follow: converge to the latest real sample without prediction.
  // This removes the previous overshoot/jitter while keeping motion visually smooth.
  const alpha=1-Math.exp(-dt/22);
  cursorRender.x += (cursorTarget.x-cursorRender.x)*alpha;
  cursorRender.y += (cursorTarget.y-cursorRender.y)*alpha;
  let x=Math.max(0,Math.min(cursorTarget.width-1,cursorRender.x));
  let y=Math.max(0,Math.min(cursorTarget.height-1,cursorRender.y));
  let nx=x/cursorTarget.width, ny=y/cursorTarget.height;
  if(rotated){
    const tx=nx, ty=ny;
    nx=1-ty; ny=tx;
  }
  const px=vr.left-wr.left+nx*vr.width;
  const py=vr.top-wr.top+ny*vr.height;
  cursor.style.transform='translate3d('+Math.round(px*10)/10+'px,'+Math.round(py*10)/10+'px,0)';
  cursor.style.display='block';
  // Keep rendering while moving toward the latest sample; stop once essentially settled.
  if(Math.abs(cursorTarget.x-cursorRender.x)>0.15 || Math.abs(cursorTarget.y-cursorRender.y)>0.15){
    cursorRenderRAF=requestAnimationFrame(renderCursor);
  }
}
function updateCursor(c){ setCursorSample(c); }

function resizeTileCanvas(){
  const w=video.videoWidth||expectedW||sourceW;
  const h=video.videoHeight||expectedH||sourceH;
  if(w>0&&h>0){
    if(tileCanvas.width!==w||tileCanvas.height!==h){ tileCanvas.width=w; tileCanvas.height=h; }
    if(tileScratch.width!==w||tileScratch.height!==h){ tileScratch.width=w; tileScratch.height=h; }
  }
}
function scrollTileCanvas(dy){
  if(!tileCtx || !tileScratchCtx) return;
  resizeTileCanvas();
  if(tileCanvas.width<=0||tileCanvas.height<=0) return;
  tileScratchCtx.clearRect(0,0,tileScratch.width,tileScratch.height);
  tileScratchCtx.drawImage(tileCanvas,0,0);
  tileCtx.clearRect(0,0,tileCanvas.width,tileCanvas.height);
  tileCtx.drawImage(tileScratch,0,dy);
}
function setTileDisplayMode(mode){
  if(mode==='tile-pending'){
    tilePending=true;
    return;
  }
  if(mode==='tile'){
    tileMode=true; tilePending=false;
    tileCanvas.style.display='block'; video.style.visibility='hidden';
    // drawBaseFromVideo() has just copied the current decoded frame. ACK that
    // exact frame so Go can safely begin applying ordered scroll deltas.
    if(tileChannel && tileChannel.readyState==='open'){
      try{ tileChannel.send(JSON.stringify({type:'base-ready'})); }catch(e){}
    }
  }else{
    tileMode=false; tilePending=false;
    tileCanvas.style.display='none'; video.style.visibility='visible';
  }
  applyVideoLayout();
}
function drawBaseFromVideo(){
  // Avoid copying a 1920x1080 frame on every callback. It is only needed while
  // a pending tile-mode transition is waiting for its H264 base frame.
  if(!tileCtx || !video.videoWidth || !video.videoHeight || !tilePending || tileMode) return;
  resizeTileCanvas();
  tileCtx.drawImage(video,0,0,tileCanvas.width,tileCanvas.height);
  setTileDisplayMode('tile');
}
function handleTileMessage(data){
  if(typeof data==='string'){
    try{
      const x=JSON.parse(data);
      if(x.type==='mode') setTileDisplayMode(x.mode);
      else if(x.type==='scroll') scrollTileCanvas(Number(x.dy)||0);
    }catch(e){}
    return;
  }
  const b=data instanceof ArrayBuffer ? data : data?.buffer;
  if(!b || b.byteLength<24) return;
  const dv=new DataView(b);
  if(dv.getUint32(0,false)!==0x54494c45) return;
  const x=dv.getUint16(8,false), y=dv.getUint16(10,false), w=dv.getUint16(12,false), h=dv.getUint16(14,false);
  const len=dv.getUint32(16,false);
  if(24+len>b.byteLength || w===0 || h===0) return;
  const blob=new Blob([b.slice(24,24+len)],{type:'image/jpeg'});
  createImageBitmap(blob).then(img=>{
    resizeTileCanvas();
    tileCtx.drawImage(img,x,y,w,h);
    img.close();
    if(!tileMode){ tilePending=true; setTileDisplayMode('tile'); }
  }).catch(()=>{});
}
function setupTileChannel(){
  try{
    tileChannel=pc.createDataChannel('tile-delta',{ordered:true});
    tileChannel.binaryType='arraybuffer';
    tileChannel.onmessage=e=>handleTileMessage(e.data);
    tileChannel.onopen=()=>console.log('[TILE] DataChannel open');
    tileChannel.onclose=()=>{tileChannel=null;setTileDisplayMode('full');};
  }catch(e){ console.warn('[TILE] channel unavailable',e); }
}

async function refreshStatus(){
  try{
    const r=await fetch('/debug/status',{cache:'no-store'});
    if(!r.ok)return;
    const s=await r.json();
    expectedW=Number(s.source?.expectedWidth||0);
    expectedH=Number(s.source?.expectedHeight||0);
    sourceW=Number(s.source?.width||0);
    sourceH=Number(s.source?.height||0);
    applyVideoLayout();
  }catch(e){}
}

let cursorPollBusy=false;
let cursorPollTimer=0;
async function pollCursor(){
  if(cursorPollBusy){ cursorPollTimer=setTimeout(pollCursor,20); return; }
  cursorPollBusy=true;
  try{
    const r=await fetch('/cursor',{cache:'no-store'});
    if(r.ok) updateCursor(await r.json());
  }catch(e){}
  finally{
    cursorPollBusy=false;
    cursorPollTimer=setTimeout(pollCursor,20);
  }
}

async function enterFullscreen(){
  try{
    if(!document.fullscreenElement){
      await (wrap.requestFullscreen?wrap.requestFullscreen():video.requestFullscreen());
    }else if(document.exitFullscreen){
      await document.exitFullscreen();
    }
  }catch(e){ console.warn('[WebRTC-FS] fullscreen failed',e); }
}
function updateFullscreenUI(){
  const fs=!!document.fullscreenElement;
  if(fullscreenBtn) fullscreenBtn.textContent=fs?'退出全屏':'全屏';
  setTimeout(applyVideoLayout,50);
}
fullscreenBtn.onclick=enterFullscreen;
wrap.addEventListener('dblclick',enterFullscreen);
document.addEventListener('fullscreenchange',updateFullscreenUI);
window.addEventListener('resize',applyVideoLayout);
document.getElementById('reconnect').onclick=()=>start(true);
soundBtn.onclick=async()=>{audioEnabled.checked=true;audioOut.muted=false;try{await audioOut.play();}catch(e){console.warn('[WebRTC-AUDIO] manual play failed',e)}};
audioEnabled.onchange=()=>{audioOut.muted=!audioEnabled.checked;start(true).catch(()=>{})};
let autoRetryTimer=null;
let starting=false;
function scheduleAutoReconnect(delay=1200){
  if(autoRetryTimer) return;
  autoRetryTimer=setTimeout(()=>{ autoRetryTimer=null; start(true).catch(()=>{}); },delay);
}
async function start(auto=false){
  if(starting) return;
  starting=true;
  try {
    if(pc){try{pc.close()}catch(e){}pc=null;}
    setStatus('creating peer...');
    pc=new RTCPeerConnection({iceServers:[]});
    setupTileChannel();
    const audioOn=audioEnabled.checked;
    pc.addTransceiver('video',{direction:'recvonly'});
    if(audioOn) pc.addTransceiver('audio',{direction:'recvonly'});
    pc.ontrack=e=>{
      const stream=(e.streams&&e.streams[0])?e.streams[0]:new MediaStream([e.track]);
      if(e.track.kind==='audio'){
        console.log('[WebRTC-AUDIO] remote audio track received');
        audioOut.srcObject=stream; audioOut.muted=false;
        audioOut.play().then(()=>console.log('[WebRTC-AUDIO] playback started')).catch(err=>console.warn('[WebRTC-AUDIO] autoplay blocked',err));
      }else{
        video.srcObject=stream; video.play().catch(()=>{}); applyVideoLayout();
      }
    };
    video.onloadedmetadata=()=>{
      sourceW=video.videoWidth; sourceH=video.videoHeight;
      resizeTileCanvas();
      applyVideoLayout();
    };
    if('requestVideoFrameCallback' in HTMLVideoElement.prototype){
      const onVideoFrame=()=>{ drawBaseFromVideo(); video.requestVideoFrameCallback(onVideoFrame); };
      video.requestVideoFrameCallback(onVideoFrame);
    }else{ video.addEventListener('timeupdate',drawBaseFromVideo); }
    pc.oniceconnectionstatechange=()=>{
      const st=pc?pc.iceConnectionState:'closed';
      setStatus('ICE: '+st);
      if((st==='failed'||st==='disconnected')&&auto) scheduleAutoReconnect(1000);
    };
    pc.onconnectionstatechange=()=>{
      const st=pc?pc.connectionState:'closed';
      setStatus('PC: '+st);
      if((st==='failed'||st==='disconnected'||st==='closed')&&auto) scheduleAutoReconnect(1200);
      else if(st==='connected') setTimeout(applyVideoLayout,50);
    };
    await refreshStatus();
    const offer=await pc.createOffer({offerToReceiveVideo:true});
    await pc.setLocalDescription(offer);
    await new Promise(resolve=>{
      if(pc.iceGatheringState==='complete')return resolve();
      const timer=setTimeout(resolve,5000);
      pc.onicegatheringstatechange=()=>{
        if(pc.iceGatheringState==='complete'){clearTimeout(timer);resolve();}
      };
    });
    console.log('[WebRTC-AUDIO] offer audio='+audioOn);
    const res=await fetch('/offer?audio='+(audioOn?'1':'0'),{method:'POST',headers:{'Content-Type':'application/sdp'},body:pc.localDescription.sdp});
    if(!res.ok)throw new Error('HTTP '+res.status+' '+await res.text());
    await pc.setRemoteDescription({type:'answer',sdp:await res.text()});
    await refreshStatus();
    setStatus('connected');
    setTimeout(applyVideoLayout,100);
  } catch(e) {
    console.error('[WebRTC]',e);
    setStatus('error: '+e.message);
    if(pc){try{pc.close()}catch(_){}pc=null;}
    if(auto) scheduleAutoReconnect(1200);
    throw e;
  } finally {
    starting=false;
  }
}
start(true).catch(()=>{});
pollCursor(); setInterval(refreshStatus,1000);
</script>
</body>
</html>`

const dlnaHTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VirtualDisplay DLNA</title>
<style>
html,body{margin:0;padding:0;background:#111;color:#ddd;font-family:system-ui,sans-serif;min-height:100%}
body{display:flex;align-items:center;justify-content:center;padding:24px;box-sizing:border-box}
.card{width:min(560px,100%);background:#202020;border:1px solid #444;border-radius:10px;padding:24px;box-sizing:border-box;box-shadow:0 10px 40px rgba(0,0,0,.35)}
h1{margin:0 0 18px;font-size:22px}
.row{display:flex;gap:10px;align-items:center;margin:12px 0}
label{font-size:14px;color:#aaa;min-width:90px}
input{flex:1;box-sizing:border-box;background:#111;color:#eee;border:1px solid #555;border-radius:6px;padding:10px 12px;font-size:15px}
button{background:#333;color:#eee;border:1px solid #666;border-radius:6px;padding:9px 14px;cursor:pointer}
button:hover{background:#444}
#status{margin-top:16px;min-height:24px;color:#9ad;white-space:pre-wrap;word-break:break-word}
.hint{font-size:12px;color:#888;line-height:1.5;margin-top:14px}
a{color:#9ad}
</style>
</head>
<body>
<div class="card">
<h1>DLNA 投屏</h1>
<div class="row"><label for="dlnaIp">电视 IP</label><input id="dlnaIp" placeholder="例如 10.26.80.23" autocomplete="off"><button id="dlnaSearch">搜索设备</button></div>
<div id="deviceList" style="margin:8px 0 14px"></div>
<div class="row">
<button id="dlnaStart">开始投屏</button>
<button id="dlnaStop">停止投屏</button>
<a href="/">返回 WebRTC</a>
</div>
<div id="status">准备就绪</div>
<div class="hint">可直接输入电视 IP，也可以点击“搜索设备”查看局域网内检测到的 DLNA 播放器候选。点击候选即可填入 IP；投屏链路及转码实现保持不变。</div>
</div>
<script>
const ip=document.getElementById('dlnaIp');
const status=document.getElementById('status');
const start=document.getElementById('dlnaStart');
const stop=document.getElementById('dlnaStop');
const search=document.getElementById('dlnaSearch');
const deviceList=document.getElementById('deviceList');
function setStatus(s){status.textContent=s;}
function renderDevices(devices){
  deviceList.innerHTML='';
  if(!devices || !devices.length){
    deviceList.innerHTML='<div style="padding:10px 12px;border:1px dashed #444;border-radius:6px;color:#888;font-size:13px">未发现 DLNA 播放设备</div>';
    return;
  }
  for(const d of devices){
    const b=document.createElement('button');
    b.style.cssText='display:block;width:100%;text-align:left;margin:6px 0;padding:10px 12px;background:#181818;border:1px solid #444;border-radius:6px;color:#ddd;cursor:pointer';
    const title=d.friendlyName || d.modelName || 'DLNA Renderer';
    b.textContent=title+'  ['+d.ip+']';
    b.onclick=()=>{ip.value=d.ip;setStatus('已选择 '+title+' ('+d.ip+')');};
    deviceList.appendChild(b);
  }
}
async function searchDevices(){
  search.disabled=true; setStatus('正在搜索局域网 DLNA 设备...');
  try{
    const r=await fetch('/dlna/discover',{cache:'no-store'});
    const t=await r.text();
    if(!r.ok)throw new Error(t);
    const x=JSON.parse(t);
    renderDevices(x.devices||[]);
    setStatus((x.devices||[]).length ? '发现 '+x.devices.length+' 个设备，请选择。' : '未发现 DLNA 播放设备');
  }catch(e){setStatus('搜索失败：'+e.message)} finally{search.disabled=false;}
}
async function loadStatus(){
  try{const r=await fetch('/dlna/status',{cache:'no-store'}); if(!r.ok)return; const x=await r.json(); if(x.target)ip.value=x.target; if(x.active)setStatus('投屏中\n'+x.url);}
  catch(e){}
}
search.onclick=searchDevices;
start.onclick=async()=>{
  const v=ip.value.trim(); if(!v){setStatus('请输入电视 IP');return;}
  setStatus('正在连接 '+v+' ...'); start.disabled=true;
  try{
    const r=await fetch('/dlna/cast',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ip:v})});
    const t=await r.text(); if(!r.ok)throw new Error(t);
    let x={}; try{x=JSON.parse(t)}catch(e){}
    setStatus('投屏已启动\n'+(x.url||''));
  }catch(e){setStatus('失败：'+e.message)} finally{start.disabled=false;}
};
stop.onclick=async()=>{
  stop.disabled=true;
  try{const r=await fetch('/dlna/stop',{method:'POST'}); const t=await r.text(); if(!r.ok)throw new Error(t); setStatus('已停止投屏');}
  catch(e){setStatus('失败：'+e.message)} finally{stop.disabled=false;}
};
loadStatus();
setInterval(loadStatus,2000);
</script>
</body>
</html>`

type streamHeader struct {
	Width  int
	Height int
}

type h264Frame struct {
	seq        uint64
	ptsUs      int64
	flags      int
	data       []byte // normalized Annex-B access unit
	receivedAt time.Time
}

type h264Source struct {
	addr           string
	token          string
	displayID      int
	expectedWidth  int
	expectedHeight int

	mu             sync.RWMutex
	header         streamHeader
	config         []byte
	sps            []byte
	pps            []byte
	profileLevelID string
	latest         *h264Frame
	latestKey      *h264Frame
	history        []*h264Frame
	seq            uint64
	changed        chan struct{}
	keyRequest     uint64

	// Persistent daemon negotiation socket keeps the server-side session alive.
	sessionMu sync.Mutex
	session   net.Conn
	sessionID int32

	// Current ROLE_VIDEO socket. Protected so diagnostics/recovery can close it safely.
	videoMu   sync.Mutex
	videoConn net.Conn

	shmMu sync.RWMutex
	shm   *shmTransport

	// Diagnostics / stream state.
	connected        bool
	connectCount     uint64
	frames           uint64
	keyFrames        uint64
	configFrames     uint64
	bytes            uint64
	lastFrameAt      time.Time
	lastFrameSize    int
	lastError        string
	pliCount         uint64
	lastPLIAt        time.Time
	lastKeyRequestAt time.Time
	framesSent       uint64
	keyFramesSent    uint64
	configSent       uint64
	waitedNoConfig   uint64
	frameOverruns    uint64
	recoveryCount    uint64
	cursor           cursorState

	// Audio diagnostics/state.
	audioEnabled   bool
	audioMu        sync.Mutex
	audioConn      net.Conn
	audioSeq       uint64
	audioChanged   chan struct{}
	audioHistory   []*audioFrame
	audioFrames    uint64
	audioBytes     uint64
	lastAudioAt    time.Time
	lastAudioSize  int
	audioCodecID   uint32
	audioProtocol  string
	audioStarted   bool
	audioLastError string

	dlnaMu       sync.Mutex
	dlnaCancel   context.CancelFunc
	dlnaTargetIP string
	dlnaURL      string

	// Raw-frame tile delta side channel. This is intentionally independent of
	// the H264/DLNA paths: Android scrcpy supplies composed RGBA frames locally,
	// the gateway compares 256x256 tiles, and only small JPEG deltas are sent
	// over a WebRTC DataChannel while the video track falls back to H264 when a
	// large portion of the screen changes.
	tileMu       sync.RWMutex
	rawStartOnce sync.Once
	tilePeers    map[*webrtc.DataChannel]struct{}
	tileMode     bool
	tileReady    bool
	tileLowCount int
	rawWidth     int
	rawHeight    int
	rawLastError string
}

func main() {
	testMode := flag.Bool("t", false, "test mode: allow direct startup without DISPLAY_ID; defaults to display 0")
	flagDisplayID := flag.Int("display-id", -1, "scrcpy display id; overrides DISPLAY_ID")
	flagDaemon := flag.String("daemon", "", "scrcpy daemon address; overrides DAEMON_ADDR")
	flagListen := flag.String("listen", "", "HTTP listen address; overrides LISTEN")
	flagToken := flag.String("token", "", "scrcpy daemon token; overrides DAEMON_TOKEN")
	flagExpectedWidth := flag.Int("expected-width", -1, "expected virtual display width; overrides EXPECTED_WIDTH")
	flagExpectedHeight := flag.Int("expected-height", -1, "expected virtual display height; overrides EXPECTED_HEIGHT")
	flagCursorURL := flag.String("cursor-url", "", "cursor JSON endpoint; overrides CURSOR_URL")
	flagAudio := flag.Bool("audio", true, "enable scrcpy audio capture and WebRTC audio")
	flag.Parse()

	daemonAddr := getenv("DAEMON_ADDR", "127.0.0.1:27183")
	if *flagDaemon != "" {
		daemonAddr = *flagDaemon
	}
	token := os.Getenv("DAEMON_TOKEN")
	if *flagToken != "" {
		token = *flagToken
	}
	displayID := -1
	if *flagDisplayID >= 0 {
		displayID = *flagDisplayID
	} else if displayEnv := strings.TrimSpace(os.Getenv("DISPLAY_ID")); displayEnv != "" {
		var err error
		displayID, err = strconv.Atoi(displayEnv)
		if err != nil || displayID < 0 {
			log.Fatalf("invalid DISPLAY_ID=%q", displayEnv)
		}
	} else if *testMode {
		// Explicit test mode may intentionally bind the daemon's default display.
		displayID = 0
	} else {
		log.Fatalf("DISPLAY_ID is required; use -t only for direct test startup or set DISPLAY_ID explicitly")
	}
	expectedWidth, _ := strconv.Atoi(getenv("EXPECTED_WIDTH", "0"))
	expectedHeight, _ := strconv.Atoi(getenv("EXPECTED_HEIGHT", "0"))
	if *flagExpectedWidth >= 0 {
		expectedWidth = *flagExpectedWidth
	}
	if *flagExpectedHeight >= 0 {
		expectedHeight = *flagExpectedHeight
	}
	listen := getenv("LISTEN", ":19000")
	if *flagListen != "" {
		listen = *flagListen
	}
	cursorURL := os.Getenv("CURSOR_URL")
	if *flagCursorURL != "" {
		cursorURL = *flagCursorURL
	}

	source := newH264Source(daemonAddr, token, displayID, expectedWidth, expectedHeight)
	source.audioEnabled = *flagAudio
	log.Printf("[AUDIO-DIAG] client audio enabled=%t", source.audioEnabled)
	go source.run()
	if source.audioEnabled {
		go source.runAudio()
	}
	if cursorURL != "" {
		go source.pollCursorURL(cursorURL)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/offer", func(w http.ResponseWriter, r *http.Request) { handleOffer(source, w, r) })
	mux.HandleFunc("/cursor", func(w http.ResponseWriter, r *http.Request) { handleCursor(source, w, r) })
	mux.HandleFunc("/debug/status", func(w http.ResponseWriter, r *http.Request) { handleDebugStatus(source, w, r) })
	mux.HandleFunc("/dlna", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/dlna" && r.URL.Path != "/dlna/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, dlnaHTML)
	})
	mux.HandleFunc("/dlna/cast", func(w http.ResponseWriter, r *http.Request) { handleDLNACast(source, w, r) })
	mux.HandleFunc("/dlna/stop", func(w http.ResponseWriter, r *http.Request) { handleDLNAStop(source, w, r) })
	mux.HandleFunc("/dlna/status", func(w http.ResponseWriter, r *http.Request) { handleDLNAStatus(source, w, r) })
	mux.HandleFunc("/dlna/discover", func(w http.ResponseWriter, r *http.Request) { handleDLNADiscover(w, r) })
	mux.HandleFunc(dlnaStreamPath, func(w http.ResponseWriter, r *http.Request) { source.serveDLNAStream(w, r) })
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/index.html" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, browserHTML)
	})

	log.Printf("WebRTC gateway listening on %s; scrcpy daemon=%s displayId=%d", listen, daemonAddr, displayID)
	log.Fatal(http.ListenAndServe(listen, mux))
}
func handleCursor(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	src.mu.RLock()
	c := src.cursor
	src.mu.RUnlock()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(c)
}

type cursorState struct {
	Visible bool `json:"visible"`
	X       int  `json:"x"`
	Y       int  `json:"y"`
	Width   int  `json:"width"`
	Height  int  `json:"height"`
}

type debugStatus struct {
	Source struct {
		Address        string `json:"address"`
		Connected      bool   `json:"connected"`
		ConnectCount   uint64 `json:"connectCount"`
		Width          int    `json:"width"`
		Height         int    `json:"height"`
		ExpectedWidth  int    `json:"expectedWidth"`
		ExpectedHeight int    `json:"expectedHeight"`
		DisplayID      int    `json:"displayId"`
		Frames         uint64 `json:"frames"`
		KeyFrames      uint64 `json:"keyFrames"`
		ConfigFrames   uint64 `json:"configFrames"`
		Bytes          uint64 `json:"bytes"`
		LastFrameSize  int    `json:"lastFrameSize"`
		LastFrameAt    string `json:"lastFrameAt,omitempty"`
		ProfileLevelID string `json:"profileLevelId,omitempty"`
		LastError      string `json:"lastError,omitempty"`
		PLI            uint64 `json:"pli"`
		FramesSent     uint64 `json:"framesSent"`
		KeyFramesSent  uint64 `json:"keyFramesSent"`
		ConfigSent     uint64 `json:"configSent"`
		WaitedNoConfig uint64 `json:"waitedNoConfig"`
		AudioEnabled   bool   `json:"audioEnabled"`
		AudioCodecID   uint32 `json:"audioCodecId"`
		AudioProtocol  string `json:"audioProtocol,omitempty"`
		AudioFrames    uint64 `json:"audioFrames"`
		AudioBytes     uint64 `json:"audioBytes"`
		LastAudioSize  int    `json:"lastAudioSize"`
		LastAudioAt    string `json:"lastAudioAt,omitempty"`
		AudioStarted   bool   `json:"audioStarted"`
		AudioError     string `json:"audioError,omitempty"`
	} `json:"source"`
	WebRTC struct {
		Note string `json:"note,omitempty"`
	} `json:"webrtc"`
}

func handleDebugStatus(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	src.mu.RLock()
	st := debugStatus{}
	st.Source.Address = src.addr
	st.Source.Connected = src.connected
	st.Source.ConnectCount = src.connectCount
	st.Source.Width = src.header.Width
	st.Source.Height = src.header.Height
	st.Source.ExpectedWidth = src.expectedWidth
	st.Source.ExpectedHeight = src.expectedHeight
	st.Source.DisplayID = src.displayID
	st.Source.Frames = src.frames
	st.Source.KeyFrames = src.keyFrames
	st.Source.ConfigFrames = src.configFrames
	st.Source.Bytes = src.bytes
	st.Source.LastFrameSize = src.lastFrameSize
	if !src.lastFrameAt.IsZero() {
		st.Source.LastFrameAt = src.lastFrameAt.Format(time.RFC3339Nano)
	}
	st.Source.ProfileLevelID = src.profileLevelID
	st.Source.LastError = src.lastError
	st.Source.PLI = src.pliCount
	st.Source.FramesSent = src.framesSent
	st.Source.KeyFramesSent = src.keyFramesSent
	st.Source.ConfigSent = src.configSent
	st.Source.WaitedNoConfig = src.waitedNoConfig
	st.Source.AudioEnabled = src.audioEnabled
	st.Source.AudioCodecID = src.audioCodecID
	st.Source.AudioProtocol = src.audioProtocol
	st.Source.AudioFrames = src.audioFrames
	st.Source.AudioBytes = src.audioBytes
	st.Source.LastAudioSize = src.lastAudioSize
	if !src.lastAudioAt.IsZero() {
		st.Source.LastAudioAt = src.lastAudioAt.Format(time.RFC3339Nano)
	}
	st.Source.AudioStarted = src.audioStarted
	st.Source.AudioError = src.audioLastError
	src.mu.RUnlock()
	st.WebRTC.Note = "peer count is not persisted; inspect gateway log for peer state"
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(st)
}

func handleOffer(src *h264Source, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	log.Printf("HTTP /offer from %s", r.RemoteAddr)
	offerSDP, err := io.ReadAll(io.LimitReader(r.Body, 256*1024))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	audioWanted := r.URL.Query().Get("audio") != "0"
	log.Printf("[AUDIO-DIAG] /offer peer=%s audio=%t", r.RemoteAddr, audioWanted)

	// Wait briefly for SPS/PPS. Without codec configuration the browser may
	// successfully establish WebRTC but cannot initialize its H264 decoder.
	profile := src.waitProfile(3 * time.Second)
	if profile == "" {
		profile = defaultH264ProfileLevelID
		log.Printf("[H264-SDP] WARNING: no SPS received before offer; using fallback profile-level-id=%s", profile)
	} else {
		log.Printf("[H264-SDP] source SPS profile-level-id=%s", profile)
	}

	fmtp := "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=" + profile
	m := &webrtc.MediaEngine{}
	if err = m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:    webrtc.MimeTypeH264,
			ClockRate:   90000,
			SDPFmtpLine: fmtp,
			RTCPFeedback: []webrtc.RTCPFeedback{
				{Type: "nack"}, {Type: "nack", Parameter: "pli"}, {Type: "ccm", Parameter: "fir"},
			},
		}, PayloadType: 102,
	}, webrtc.RTPCodecTypeVideo); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if audioWanted {
		if err = m.RegisterCodec(webrtc.RTPCodecParameters{
			RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2, SDPFmtpLine: "minptime=10;stereo=1;useinbandfec=1"},
			PayloadType:        111,
		}, webrtc.RTPCodecTypeAudio); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	// 创建拦截器注册表
	i := &interceptor.Registry{}

	// 注册默认拦截器 (提供 NACK 丢包重传、RTCP 报告等基础能力)
	if err = webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// 将拦截器注入到 API 中
	api := webrtc.NewAPI(webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() != "tile-delta" {
			return
		}
		src.addTilePeer(dc)
		dc.OnClose(func() { src.removeTilePeer(dc) })
		dc.OnError(func(err error) { log.Printf("[TILE] DataChannel error: %v", err) })
		log.Printf("[TILE] DataChannel attached label=%s", dc.Label())
	})

	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264, ClockRate: 90000, SDPFmtpLine: fmtp},
		"video", "virtualdisplay")
	if err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	if _, err = pc.AddTrack(track); err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	var audioTrack *webrtc.TrackLocalStaticSample
	if audioWanted {
		audioTrack, err = webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2, SDPFmtpLine: "minptime=10;stereo=1;useinbandfec=1"}, "audio", "virtualdisplay")
		if err != nil {
			http.Error(w, err.Error(), 500)
			_ = pc.Close()
			return
		}
		if _, err = pc.AddTrack(audioTrack); err != nil {
			http.Error(w, err.Error(), 500)
			_ = pc.Close()
			return
		}
	}

	// Drain and inspect RTCP. A PLI/FIR means the browser needs a fresh keyframe.
	if sender := pc.GetSenders(); len(sender) > 0 {
		go func() {
			for {
				pkts, _, e := sender[0].ReadRTCP()
				if e != nil {
					return
				}
				for _, pkt := range pkts {
					switch pkt.(type) {
					case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
						now := time.Now()
						src.mu.Lock()
						src.pliCount++
						src.lastPLIAt = now
						// Repeated PLI/FIR packets can arrive every few milliseconds while the
						// decoder is waiting. Do not turn that into an IDR storm; one recovery
						// request per 500 ms is sufficient because the latest keyframe is cached.
						request := src.lastKeyRequestAt.IsZero() || now.Sub(src.lastKeyRequestAt) >= 500*time.Millisecond
						if request {
							src.keyRequest++
							src.lastKeyRequestAt = now
						}
						src.mu.Unlock()
						if request {
							log.Printf("[H264-RTCP] keyframe requested by browser (%T)", pkt)
						}
					case *rtcp.TransportLayerNack:
						// The source is TCP, so packet loss is recovered by the H264 keyframe path rather than RTP retransmission.
					}
				}
			}
		}()
	}

	pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		log.Printf("peer=%s audio=%t", st.String(), audioWanted)
		if st == webrtc.PeerConnectionStateConnected && audioWanted {
			go src.startAudioWhenReady(pc)
		}
		if st == webrtc.PeerConnectionStateFailed || st == webrtc.PeerConnectionStateClosed {
			_ = pc.Close()
		}
	})

	if err = pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: string(offerSDP)}); err != nil {
		http.Error(w, err.Error(), 400)
		_ = pc.Close()
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err = pc.SetLocalDescription(answer); err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	<-gatherComplete
	local := pc.LocalDescription()
	if local == nil {
		http.Error(w, "missing local description", 500)
		_ = pc.Close()
		return
	}

	log.Printf("WebRTC answer ready: peer=%s profile-level-id=%s audio=%t", r.RemoteAddr, profile, audioWanted)
	go src.pipeTo(track, pc)
	if audioTrack != nil {
		go src.pipeAudioTo(audioTrack, pc)
	}
	w.Header().Set("Content-Type", "application/sdp")
	_, _ = io.WriteString(w, local.SDP)
}

func (s *h264Source) waitProfile(timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		s.mu.RLock()
		profile := s.profileLevelID
		haveConfig := len(s.sps) > 0 && len(s.pps) > 0
		ch := s.changed
		s.mu.RUnlock()
		if profile != "" && haveConfig {
			return profile
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return profile
		}
		t := time.NewTimer(remaining)
		select {
		case <-ch:
			if !t.Stop() {
				select {
				case <-t.C:
				default:
				}
			}
		case <-t.C:
			return profile
		}
	}
}

func newH264Source(addr, token string, displayID, expectedWidth, expectedHeight int) *h264Source {
	h := streamHeader{Width: expectedWidth, Height: expectedHeight}
	return &h264Source{
		addr:           addr,
		token:          token,
		displayID:      displayID,
		expectedWidth:  expectedWidth,
		expectedHeight: expectedHeight,
		header:         h,
		changed:        make(chan struct{}),
		audioEnabled:   true,
		audioChanged:   make(chan struct{}),
		audioCodecID:   audioCodecOpusID,
		tilePeers:      make(map[*webrtc.DataChannel]struct{}),
	}
}

func (s *h264Source) run() {
	for {
		if err := s.readOnce(); err != nil {
			s.mu.Lock()
			s.lastError = err.Error()
			s.connected = false
			s.mu.Unlock()
			log.Printf("scrcpy daemon source: %v", err)
			time.Sleep(500 * time.Millisecond)
		}
	}
}

func (s *h264Source) readOnce() error {
	if err := s.ensureSession(); err != nil {
		return err
	}

	s.shmMu.RLock()
	shm := s.shm
	s.shmMu.RUnlock()
	if shm != nil && shm.video != nil {
		s.mu.Lock()
		s.connected = true
		s.connectCount++
		s.lastError = ""
		s.mu.Unlock()
		if err := s.readShmVideoStream(shm.video); err != nil {
			if err != errShmUnavailable {
				log.Printf("internal SHM video stopped: %v; falling back to 27183", err)
			}
		}
		return nil
	}

	conn, err := s.openVideoSocket()
	if err != nil {
		// If the configured session vanished, force a fresh negotiation on next attempt.
		s.dropSession()
		return err
	}

	s.videoMu.Lock()
	s.videoConn = conn
	s.videoMu.Unlock()

	s.mu.Lock()
	s.connected = true
	s.connectCount++
	s.lastError = ""
	s.mu.Unlock()

	defer func() {
		_ = conn.Close()
		s.videoMu.Lock()
		if s.videoConn == conn {
			s.videoConn = nil
		}
		s.videoMu.Unlock()
		s.mu.Lock()
		s.connected = false
		s.mu.Unlock()
	}()

	return s.readScrcpyVideoStream(conn)
}

func (s *h264Source) ensureSession() error {
	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()
	if s.session != nil {
		return nil
	}

	log.Printf("scrcpy daemon negotiation connecting: %s", s.addr)
	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		return err
	}
	configureTCP(c)

	br := bufio.NewReaderSize(c, 64*1024)
	if err := writeU8(c, roleNegotiation); err != nil {
		_ = c.Close()
		return err
	}

	sid, err := readI32(br)
	if err != nil {
		_ = c.Close()
		return err
	}

	if s.token != "" {
		if err := writeString32(c, s.token); err != nil {
			_ = c.Close()
			return err
		}
	}

	// Device meta is emitted by ClientSession after authentication/session creation.
	meta := make([]byte, 64)
	if _, err := io.ReadFull(br, meta); err != nil {
		_ = c.Close()
		return err
	}

	options := strings.Join([]string{
		"video=true",
		"audio=" + strconv.FormatBool(s.audioEnabled),
		"audio_codec=opus",
		// Use Android 13+ playback capture instead of REMOTE_SUBMIX.
		// audio_dup=true keeps the captured playback audible on the device
		// while the same audio is forwarded to the WebRTC peer.
		"audio_source=output",
		"audio_dup=false",
		"video_codec=h264",
		// Internal WebRTC profile: reduce the old 8 Mbps/60 fps default and
		// prevent fast UI movement from producing excessive burst bandwidth.
		"video_bit_rate=6000000",
		"max_fps=30",
		"send_stream_meta=false",
		"send_frame_meta=true",
	}, "\n") + "\n"

	const seq = int64(1)
	// Internal gateway path: ask the daemon for the RAM-backed SHM bridge plus
	// the ordinary control role. ROLE_INTERNAL_SHM never opens a TCP role socket.
	entries := []roleEntry{
		{role: roleInternalSHM, displayID: int32(s.displayID)},
		{role: roleControl, displayID: int32(s.displayID)},
		// Keep the legacy video/audio declarations available as a compatibility
		// fallback for an older daemon/SHM failure and for other external clients.
		{role: roleVideo, displayID: int32(s.displayID)},
		{role: roleAudio, displayID: int32(s.displayID)},
	}
	if err := writeConfigureSession(c, seq, options, entries); err != nil {
		_ = c.Close()
		return err
	}

	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	response, err := readGenericResponse(br)
	if err != nil {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION response: %w", err)
	}
	c.SetReadDeadline(time.Time{})

	if response.sequence != seq {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION sequence mismatch: got %d want %d", response.sequence, seq)
	}
	if response.statusCode != 0 {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION failed: status=%d message=%s", response.statusCode, response.message)
	}

	s.session = c
	s.sessionID = sid
	var shm *shmTransport
	var shmErr error
	for i := 0; i < 30; i++ {
		shm, shmErr = connectInternalShm(s.displayID)
		if shmErr == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if shmErr == nil {
		s.shmMu.Lock()
		if s.shm != nil {
			s.shm.close()
		}
		s.shm = shm
		s.shmMu.Unlock()
		log.Printf("internal SHM bridge connected: displayId=%d", s.displayID)
	} else {
		log.Printf("internal SHM bridge unavailable, retaining 27183 TCP fallback: %v", shmErr)
	}
	log.Printf("scrcpy daemon session established: sessionId=%d device=%q", sid, trimCString(meta))
	return nil
}

func (s *h264Source) openVideoSocket() (net.Conn, error) {
	s.sessionMu.Lock()
	sid := s.sessionID
	sessionAlive := s.session != nil
	s.sessionMu.Unlock()
	if !sessionAlive {
		return nil, fmt.Errorf("daemon session not established")
	}

	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	configureTCP(c)

	if err := writeU8(c, roleVideo); err != nil {
		_ = c.Close()
		return nil, err
	}
	if err := writeI32(c, sid); err != nil {
		_ = c.Close()
		return nil, err
	}
	if err := writeI32(c, int32(s.displayID)); err != nil {
		_ = c.Close()
		return nil, err
	}

	br := bufio.NewReaderSize(c, 256*1024)
	ack, err := readI32(br)
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	if int(ack) != s.displayID {
		_ = c.Close()
		return nil, fmt.Errorf("ROLE_VIDEO displayId ack mismatch: got %d want %d", ack, s.displayID)
	}

	// From here the socket is exactly the daemon's scrcpy Streamer output:
	//   int32 codecId
	//   int32 sessionFlags + int32 width + int32 height
	//   repeated: uint64 ptsAndFlags + int32 payloadSize + payload
	// The buffered reader must remain associated with the connection for the whole stream.
	return &bufferedConn{Conn: c, r: br}, nil
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

var errShmUnavailable = fmt.Errorf("SHM ring unavailable")

// requestKeyframe re-bases WebRTC output onto the newest complete IDR already
// cached by the gateway. The Android daemon encoder continues running and the
// next cached keyframe will be used by pipeTo; no extra 27183 bulk-media write
// is required for SHM overruns. Rate limiting prevents a full ring from
// generating an IDR/recovery storm.
func (s *h264Source) requestKeyframe() {
	now := time.Now()
	s.mu.Lock()
	if !s.lastKeyRequestAt.IsZero() && now.Sub(s.lastKeyRequestAt) < 500*time.Millisecond {
		s.mu.Unlock()
		return
	}
	s.keyRequest++
	s.lastKeyRequestAt = now
	s.pliCount++
	s.lastPLIAt = now
	s.mu.Unlock()
	log.Printf("[H264-SHM] local keyframe rebase requested")
}

func (s *h264Source) readShmVideoStream(r *shmMappedRing) error {
	var want uint64 = 1
	for {
		pts, flags, w, h, data, ok, overrun := r.readSeq(want)
		if overrun {
			s.requestKeyframe()
			want = r.latestSeq() + 1
			continue
		}
		if !ok {
			time.Sleep(1 * time.Millisecond)
			continue
		}
		want++
		if flags&shmRingFlagMeta != 0 {
			s.mu.Lock()
			s.header.Width = w
			s.header.Height = h
			s.mu.Unlock()
			continue
		}
		if flags&shmRingFlagPacket == 0 {
			continue
		}
		config := flags&shmRingFlagConfig != 0
		key := flags&shmRingFlagKey != 0
		normalized, sp, pp, okNorm := normalizeH264(data)
		if !okNorm {
			return fmt.Errorf("unsupported H264 SHM payload size=%d first=%s", len(data), firstBytes(data, 16))
		}
		if hasNALType(normalized, 5) {
			key = true
		}
		ff := 0
		if config {
			ff |= flagConfig
		}
		if key {
			ff |= flagKeyFrame
		}
		now := time.Now()
		s.mu.Lock()
		if len(sp) > 0 {
			s.sps = append([]byte(nil), sp...)
			if p := profileLevelID(sp); p != "" {
				s.profileLevelID = p
			}
		}
		if len(pp) > 0 {
			s.pps = append([]byte(nil), pp...)
		}
		if len(s.sps) > 0 && len(s.pps) > 0 {
			s.config = annexBJoin(s.sps, s.pps)
		}
		if config && len(s.config) > 0 {
			s.configFrames++
		}
		s.seq++
		f := &h264Frame{seq: s.seq, ptsUs: pts, flags: ff, data: normalized, receivedAt: now}
		s.latest = f
		s.history = append(s.history, f)
		cutoff := now.Add(-frameHistoryWindow)
		firstKeep := 0
		for firstKeep < len(s.history) && (len(s.history)-firstKeep > frameHistoryMaxFrames || s.history[firstKeep].receivedAt.Before(cutoff)) {
			firstKeep++
		}
		if firstKeep > 0 {
			s.history = append([]*h264Frame(nil), s.history[firstKeep:]...)
		}
		s.frames++
		s.bytes += uint64(len(normalized))
		s.lastFrameAt = now
		s.lastFrameSize = len(normalized)
		if key {
			s.latestKey = f
			s.keyFrames++
		}
		old := s.changed
		s.changed = make(chan struct{})
		close(old)
		s.mu.Unlock()
	}
}

func (s *h264Source) readScrcpyVideoStream(conn net.Conn) error {
	bc, ok := conn.(*bufferedConn)
	if !ok {
		return fmt.Errorf("internal video connection type error")
	}

	// The daemon's FrameBroadcaster is shared per display. If another client
	// (notably the Android app itself) created it first, its original
	// send_stream_meta=false setting is baked into the broadcaster. In that
	// case the ROLE_VIDEO socket starts directly with FrameMeta, without a
	// codec-id or SessionMeta. Our gateway therefore accepts BOTH forms:
	//
	//   A) codec header + optional SessionMeta + FrameMeta
	//   B) FrameMeta-only (the normal path for the app's existing subscriber)
	//
	// We prefer B for newly-created broadcasters by requesting
	// send_stream_meta=false in ensureSession().
	hasCodecHeader := false
	codec := uint32(codecH264ID)
	if peek, e := bc.r.Peek(12); e == nil {
		first := binary.BigEndian.Uint32(peek[0:4])
		second := binary.BigEndian.Uint32(peek[4:8])
		sz := int64(int32(binary.BigEndian.Uint32(peek[8:12])))
		looksLikeCodec := first == codecH264ID
		if first == codecH264LegacyID {
			// Legacy/internal H264 is also the high 32 bits of the KEY_FRAME
			// flag, so distinguish a real codec header from a frame-meta-only
			// stream by looking for a following meta/flag word or an implausibly
			// large PTS high-half.
			looksLikeCodec = (second&0xC0000000) != 0 || second > 0x00FFFFFF
		}
		if looksLikeCodec {
			_, _ = io.ReadFull(bc.r, peek[0:4])
			codecID, e := readI32From4(peek[0:4])
			if e != nil {
				return e
			}
			codec = uint32(codecID)
			if codec != codecH264ID && codec != codecH264LegacyID {
				return fmt.Errorf("unsupported scrcpy video codec id=0x%08x (want H264=0x%08x or legacy=0x%08x)", codec, codecH264ID, codecH264LegacyID)
			}
			hasCodecHeader = true
			if codec == codecH264LegacyID {
				log.Printf("scrcpy ROLE_VIDEO reports legacy/internal H264 codec id=0x%08x", codec)
			}
		} else if first == codecH264LegacyID && sz >= 1 && sz <= maxFramePayload {
			log.Printf("scrcpy ROLE_VIDEO detected frame-meta-only legacy-keyframe stream (displayId=%d)", s.displayID)
		}
	}

	log.Printf("scrcpy ROLE_VIDEO connected: displayId=%d codec=H264 framing=%s", s.displayID, func() string {
		if hasCodecHeader {
			return "codec-header"
		}
		return "frame-meta-only"
	}())

	for {
		// scrcpy Streamer multiplexes two different 12-byte records:
		//   1) SessionMeta: uint32(flags) | uint32(width) | uint32(height)
		//      with bit31 of flags set; NO payload follows.
		//   2) FrameMeta: uint64(ptsAndFlags) | uint32(payloadSize),
		//      followed by payload bytes.
		// Read the first 4 bytes first so SessionMeta can be recognized without
		// consuming the following frame header as a gigantic payload size.
		var first4 [4]byte
		if _, err := io.ReadFull(bc.r, first4[:]); err != nil {
			return err
		}

		first := binary.BigEndian.Uint32(first4[:])
		if hasCodecHeader && first&0x80000000 != 0 {
			var metaRest [8]byte
			if _, err := io.ReadFull(bc.r, metaRest[:]); err != nil {
				return err
			}
			width := binary.BigEndian.Uint32(metaRest[0:4])
			height := binary.BigEndian.Uint32(metaRest[4:8])
			s.mu.Lock()
			s.header.Width = int(width)
			s.header.Height = int(height)
			s.mu.Unlock()
			log.Printf("scrcpy ROLE_VIDEO session meta: displayId=%d %dx%d flags=0x%08x", s.displayID, width, height, first)
			continue
		}

		// FrameMeta is exactly 12 bytes total: uint64 ptsAndFlags + uint32 payloadSize.
		// We already consumed the first 4 bytes above, so consume only the remaining
		// 4 bytes of ptsAndFlags, then the 4-byte payload size. The previous version
		// consumed 8 bytes here and therefore swallowed the first 4 bytes of the H264
		// payload (for example 67 42 00 0a), producing the bogus size 0x6742000a.
		var ptsLow4 [4]byte
		if _, err := io.ReadFull(bc.r, ptsLow4[:]); err != nil {
			return err
		}
		var ptsBytes [8]byte
		copy(ptsBytes[0:4], first4[:])
		copy(ptsBytes[4:8], ptsLow4[:])
		rawFlags := binary.BigEndian.Uint64(ptsBytes[:])
		var size4 [4]byte
		if _, err := io.ReadFull(bc.r, size4[:]); err != nil {
			return err
		}
		size := int32(binary.BigEndian.Uint32(size4[:]))
		if size <= 0 || size > maxFramePayload {
			return fmt.Errorf("invalid scrcpy frame size=%d", size)
		}

		data := make([]byte, int(size))
		if _, err := io.ReadFull(bc.r, data); err != nil {
			return err
		}

		config := (rawFlags & packetFlagConfig) != 0
		key := (rawFlags & packetFlagKeyFrame) != 0
		pts := int64(rawFlags &^ (packetFlagConfig | packetFlagKeyFrame))

		normalized, sps, pps, ok := normalizeH264(data)
		if !ok {
			return fmt.Errorf("unsupported H264 payload size=%d first=%s", len(data), firstBytes(data, 16))
		}

		if hasNALType(normalized, 5) {
			key = true
		}
		flags := 0
		if config {
			flags |= flagConfig
		}
		if key {
			flags |= flagKeyFrame
		}

		now := time.Now()

		s.mu.Lock()
		if len(sps) > 0 {
			s.sps = append([]byte(nil), sps...)
			if p := profileLevelID(sps); p != "" {
				s.profileLevelID = p
			}
		}
		if len(pps) > 0 {
			s.pps = append([]byte(nil), pps...)
		}
		if len(s.sps) > 0 && len(s.pps) > 0 {
			s.config = annexBJoin(s.sps, s.pps)
		}
		if config && len(s.config) > 0 {
			s.configFrames++
		}

		s.seq++
		f := &h264Frame{
			seq:        s.seq,
			ptsUs:      pts,
			flags:      flags,
			data:       normalized,
			receivedAt: now,
		}
		s.latest = f
		s.history = append(s.history, f)

		cutoff := now.Add(-frameHistoryWindow)
		firstKeep := 0
		for firstKeep < len(s.history) &&
			(len(s.history)-firstKeep > frameHistoryMaxFrames || s.history[firstKeep].receivedAt.Before(cutoff)) {
			firstKeep++
		}
		if firstKeep > 0 {
			s.history = append([]*h264Frame(nil), s.history[firstKeep:]...)
		}

		s.frames++
		s.bytes += uint64(len(normalized))
		s.lastFrameAt = now
		s.lastFrameSize = len(normalized)
		if key {
			s.latestKey = f
			s.keyFrames++
		}

		old := s.changed
		s.changed = make(chan struct{})
		close(old)
		s.mu.Unlock()
	}
}

type audioFrame struct {
	seq        uint64
	ptsUs      int64
	data       []byte
	duration   time.Duration
	receivedAt time.Time
}

func (s *h264Source) readShmAudioStream(r *shmMappedRing) error {
	var want uint64 = 1
	s.mu.Lock()
	s.audioCodecID = audioCodecOpusID
	s.audioProtocol = "shared-memory"
	s.audioLastError = ""
	s.mu.Unlock()
	for {
		pts, flags, _, _, data, ok, overrun := r.readSeq(want)
		if overrun {
			want = r.latestSeq() + 1
			continue
		}
		if !ok {
			time.Sleep(2 * time.Millisecond)
			continue
		}
		want++
		if flags&shmRingFlagPacket == 0 {
			continue
		}
		s.recordAudioFrame(pts, data)
	}
}

func (s *h264Source) runAudio() {
	for {
		if !s.audioEnabled {
			time.Sleep(time.Second)
			continue
		}
		if err := s.ensureSession(); err != nil {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		s.shmMu.RLock()
		shm := s.shm
		s.shmMu.RUnlock()
		if shm != nil && shm.audio != nil {
			if err := s.readShmAudioStream(shm.audio); err != nil {
				s.mu.Lock()
				s.audioLastError = err.Error()
				s.mu.Unlock()
				log.Printf("[AUDIO-DIAG] internal SHM audio stopped: %v", err)
			}
			time.Sleep(100 * time.Millisecond)
			continue
		}
		c, err := s.openRoleSocket(roleAudio)
		if err != nil {
			s.mu.Lock()
			s.audioLastError = fmt.Sprintf("open ROLE_AUDIO: %v", err)
			s.mu.Unlock()
			log.Printf("[AUDIO-DIAG] ROLE_AUDIO connect failed: %v", err)
			time.Sleep(700 * time.Millisecond)
			continue
		}
		s.audioMu.Lock()
		s.audioConn = c
		s.audioMu.Unlock()
		log.Printf("[AUDIO-DIAG] ROLE_AUDIO connected displayId=%d", s.displayID)
		br := bufio.NewReaderSize(c, 256*1024)
		if err := s.readAudioStream(br); err != nil {
			s.mu.Lock()
			s.audioLastError = err.Error()
			s.mu.Unlock()
			log.Printf("[AUDIO-DIAG] ROLE_AUDIO stopped: %v", err)
		}
		_ = c.Close()
		s.audioMu.Lock()
		if s.audioConn == c {
			s.audioConn = nil
		}
		s.audioMu.Unlock()
		time.Sleep(300 * time.Millisecond)
	}
}

func (s *h264Source) readAudioStream(br *bufio.Reader) error {
	// With send_stream_meta=false, Streamer.writeAudioHeader() deliberately
	// sends NO 4-byte codec header. The stream starts directly with the
	// 12-byte frame metadata header. The first 32 bits are the high half of
	// ptsAndFlags, so a CONFIG packet starts with 0x40000000 (bit 62), which
	// is exactly what the current server log shows.
	var first [4]byte
	if _, err := io.ReadFull(br, first[:]); err != nil {
		return fmt.Errorf("read initial audio header: %w", err)
	}
	firstU := binary.BigEndian.Uint32(first[:])

	// Explicit codec header mode (send_stream_meta=true).
	if firstU == audioCodecOpusID {
		s.mu.Lock()
		s.audioCodecID = firstU
		s.audioProtocol = "codec-header"
		s.audioLastError = ""
		s.mu.Unlock()
		log.Printf("[AUDIO-DIAG] OPUS codec header received id=0x%08x", firstU)
		return s.consumeAudioFrames(br, nil)
	}

	// Disabled-stream codes are only unambiguous when the following 8 bytes
	// do not form a valid frame header. Otherwise 0/1 can be the high 32 bits
	// of a real PTS, so parse the complete 12-byte header first.
	var rest [8]byte
	_ = s.setAudioReadDeadline(350 * time.Millisecond)
	n, err := io.ReadFull(br, rest[:])
	_ = s.clearAudioReadDeadline()
	if err != nil || n != 8 {
		if firstU == 1 {
			return fmt.Errorf("server reported fatal audio error (disable code=1)")
		}
		if firstU == 0 {
			return fmt.Errorf("server disabled audio capture (disable code=0)")
		}
		if err != nil {
			return fmt.Errorf("read initial audio frame meta: %w", err)
		}
		return fmt.Errorf("short initial audio frame meta: %d/8 bytes", n)
	}

	sz := binary.BigEndian.Uint32(rest[4:8])
	if sz == 0 || sz > maxAudioPayload {
		if firstU == 0 {
			return fmt.Errorf("server disabled audio capture (disable code=0)")
		}
		if firstU == 1 {
			return fmt.Errorf("server reported fatal audio error (disable code=1)")
		}
		return fmt.Errorf("unsupported audio codec/header 0x%08x nextSize=%d", firstU, sz)
	}

	s.mu.Lock()
	s.audioCodecID = audioCodecOpusID
	s.audioProtocol = "frame-meta-only"
	s.audioLastError = ""
	s.mu.Unlock()
	log.Printf("[AUDIO-DIAG] frame-meta-only stream detected firstHigh32=0x%08x payload=%d", firstU, sz)

	var hdr [12]byte
	binary.BigEndian.PutUint32(hdr[0:4], firstU)
	copy(hdr[4:12], rest[:])
	return s.consumeAudioFrames(br, hdr[:])
}

func (s *h264Source) consumeAudioFrames(br *bufio.Reader, initial []byte) error {
	var pending [12]byte
	havePending := len(initial) == 12
	if havePending {
		copy(pending[:], initial)
	}
	for {
		var hdr [12]byte
		if havePending {
			copy(hdr[:], pending[:])
			havePending = false
		} else if _, err := io.ReadFull(br, hdr[:]); err != nil {
			return fmt.Errorf("read audio frame meta: %w", err)
		}
		raw := binary.BigEndian.Uint64(hdr[:8])
		if raw&(uint64(1)<<63) != 0 {
			log.Printf("[AUDIO-DIAG] audio session meta %dx%d", binary.BigEndian.Uint32(hdr[4:8]), binary.BigEndian.Uint32(hdr[8:12]))
			continue
		}
		sz := binary.BigEndian.Uint32(hdr[8:12])
		if sz == 0 || sz > maxAudioPayload {
			return fmt.Errorf("invalid audio frame size=%d raw=0x%016x", sz, raw)
		}
		data := make([]byte, sz)
		if _, err := io.ReadFull(br, data); err != nil {
			return fmt.Errorf("read audio payload size=%d: %w", sz, err)
		}
		if raw&(uint64(1)<<62) != 0 {
			log.Printf("[AUDIO-DIAG] audio config packet size=%d skipped", sz)
			continue
		}
		s.recordAudioFrame(int64(raw&((uint64(1)<<61)-1)), data)
	}
}

func (s *h264Source) recordAudioFrame(ptsUs int64, data []byte) {
	s.mu.Lock()
	s.audioSeq++
	s.audioHistory = append(s.audioHistory, &audioFrame{
		seq: s.audioSeq, ptsUs: ptsUs, data: append([]byte(nil), data...),
		duration: 20 * time.Millisecond, receivedAt: time.Now(),
	})
	if len(s.audioHistory) > 400 {
		s.audioHistory = append([]*audioFrame(nil), s.audioHistory[len(s.audioHistory)-400:]...)
	}
	s.audioFrames++
	s.audioBytes += uint64(len(data))
	s.lastAudioAt = time.Now()
	s.lastAudioSize = len(data)
	s.audioLastError = ""
	old := s.audioChanged
	s.audioChanged = make(chan struct{})
	close(old)
	n := s.audioFrames
	s.mu.Unlock()
	if n <= 5 || n%250 == 0 {
		log.Printf("[AUDIO-DIAG] OPUS frame #%d pts=%d size=%d", n, ptsUs, len(data))
	}
}
func (s *h264Source) setAudioReadDeadline(d time.Duration) error {
	s.audioMu.Lock()
	c := s.audioConn
	s.audioMu.Unlock()
	if c == nil {
		return nil
	}
	return c.SetReadDeadline(time.Now().Add(d))
}
func (s *h264Source) clearAudioReadDeadline() error {
	s.audioMu.Lock()
	c := s.audioConn
	s.audioMu.Unlock()
	if c == nil {
		return nil
	}
	return c.SetReadDeadline(time.Time{})
}

func (s *h264Source) startAudioWhenReady(pc *webrtc.PeerConnection) {
	if !s.audioEnabled || pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
		return
	}
	// Audio capture/encoding is started by the daemon as soon as ROLE_AUDIO
	// is bound. Do not send the legacy 0x16 control byte here: it belongs to
	// the old on-demand audio-start path and is not part of this daemon flow.
	s.mu.Lock()
	s.audioStarted = true
	s.mu.Unlock()
	log.Printf("[AUDIO-DIAG] WebRTC peer connected; ROLE_AUDIO stream is already running")
}
func (s *h264Source) pipeAudioTo(track *webrtc.TrackLocalStaticSample, pc *webrtc.PeerConnection) {
	var last uint64
	for {
		s.mu.RLock()
		ch := s.audioChanged
		latest := s.audioSeq
		var af *audioFrame
		if last == 0 && latest > 0 {
			last = latest - 1
		}
		for i := range s.audioHistory {
			if s.audioHistory[i].seq == last+1 {
				v := *s.audioHistory[i]
				af = &v
				break
			}
		}
		s.mu.RUnlock()
		if af == nil {
			if pc.ConnectionState() == webrtc.PeerConnectionStateClosed || pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
				return
			}
			select {
			case <-ch:
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}
		if err := track.WriteSample(media.Sample{Data: af.data, PacketTimestamp: uint32((af.ptsUs * 48) / 1000), Duration: af.duration}); err != nil {
			log.Printf("[AUDIO-DIAG] WebRTC WriteSample failed: %v", err)
			return
		}
		last = af.seq
	}
}

func (s *h264Source) dropSession() {
	s.sessionMu.Lock()
	if s.session != nil {
		_ = s.session.Close()
	}
	s.session = nil
	s.sessionID = 0
	s.sessionMu.Unlock()
	s.shmMu.Lock()
	if s.shm != nil {
		s.shm.close()
		s.shm = nil
	}
	s.shmMu.Unlock()
}

func (s *h264Source) pollCursorURL(url string) {
	client := &http.Client{Timeout: 500 * time.Millisecond}
	ticker := time.NewTicker(33 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		resp, err := client.Get(url)
		if err != nil {
			continue
		}
		var c cursorState
		err = json.NewDecoder(resp.Body).Decode(&c)
		_ = resp.Body.Close()
		if err != nil {
			continue
		}
		s.mu.Lock()
		s.cursor = c
		s.mu.Unlock()
	}
}

func (s *h264Source) openRoleSocket(role int) (net.Conn, error) {
	s.sessionMu.Lock()
	sid := s.sessionID
	ok := s.session != nil
	s.sessionMu.Unlock()
	if !ok {
		return nil, fmt.Errorf("daemon session not established")
	}
	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	configureTCP(c)
	if err = writeU8(c, role); err != nil {
		c.Close()
		return nil, err
	}
	if err = writeI32(c, sid); err != nil {
		c.Close()
		return nil, err
	}
	if err = writeI32(c, int32(s.displayID)); err != nil {
		c.Close()
		return nil, err
	}
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	ack, err := readI32(c)
	_ = c.SetReadDeadline(time.Time{})
	if err != nil {
		c.Close()
		return nil, err
	}
	if int(ack) != s.displayID {
		c.Close()
		return nil, fmt.Errorf("role=%d display ack mismatch got=%d want=%d", role, ack, s.displayID)
	}
	return c, nil
}

// ---- DLNA output (H.264 -> MPEG-TS over HTTP + UPnP AVTransport) ----

type dlnaCastRequest struct {
	IP string `json:"ip"`
}

type dlnaStatus struct {
	Active bool   `json:"active"`
	Target string `json:"target,omitempty"`
	URL    string `json:"url,omitempty"`
	Error  string `json:"error,omitempty"`
}

type upnpService struct {
	ServiceType string `xml:"serviceType"`
	ControlURL  string `xml:"controlURL"`
}

type upnpDeviceDescription struct {
	Device struct {
		ServiceList struct {
			Services []upnpService `xml:"service"`
		} `xml:"serviceList"`
		Devices []struct {
			ServiceList struct {
				Services []upnpService `xml:"service"`
			} `xml:"serviceList"`
		} `xml:"deviceList>device"`
	} `xml:"device"`
}

type dlnaDiscoveredDevice struct {
	IP           string `json:"ip"`
	Location     string `json:"location,omitempty"`
	FriendlyName string `json:"friendlyName,omitempty"`
	ModelName    string `json:"modelName,omitempty"`
}

func handleDLNADiscover(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	devices := discoverDLNADevices(1800 * time.Millisecond)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"devices": devices})
}

func discoverDLNADevices(timeout time.Duration) []dlnaDiscoveredDevice {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	msg := "M-SEARCH * HTTP/1.1\r\n" +
		"HOST: 239.255.255.250:1900\r\n" +
		"MAN: \"ssdp:discover\"\r\n" +
		"MX: 1\r\n" +
		"ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
	_, _ = conn.WriteToUDP([]byte(msg), &net.UDPAddr{IP: net.IPv4(239, 255, 255, 250), Port: 1900})

	seen := make(map[string]bool)
	var out []dlnaDiscoveredDevice
	buf := make([]byte, 8192)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			break
		}
		if addr == nil || addr.IP == nil {
			continue
		}
		ip := addr.IP.To4()
		if ip == nil {
			continue
		}
		ipstr := ip.String()
		if seen[ipstr] {
			continue
		}
		h := parseHTTPHeaders(buf[:n])
		loc := strings.TrimSpace(h["location"])
		if loc == "" {
			continue
		}
		seen[ipstr] = true
		d := dlnaDiscoveredDevice{IP: ipstr, Location: loc}
		if name, model := fetchDLNAFriendlyInfo(loc); name != "" || model != "" {
			d.FriendlyName, d.ModelName = name, model
		}
		if d.FriendlyName == "" {
			d.FriendlyName = d.ModelName
		}
		out = append(out, d)
	}
	return out
}

type dlnaDeviceInfo struct {
	Device struct {
		FriendlyName string `xml:"friendlyName"`
		ModelName    string `xml:"modelName"`
	} `xml:"device"`
}

func fetchDLNAFriendlyInfo(location string) (string, string) {
	client := &http.Client{Timeout: 1200 * time.Millisecond}
	resp, err := client.Get(location)
	if err != nil {
		return "", ""
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return "", ""
	}
	var info dlnaDeviceInfo
	if err := xml.NewDecoder(io.LimitReader(resp.Body, 128*1024)).Decode(&info); err != nil {
		return "", ""
	}
	return strings.TrimSpace(info.Device.FriendlyName), strings.TrimSpace(info.Device.ModelName)
}

func handleDLNACast(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var req dlnaCastRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}
	ip := strings.TrimSpace(req.IP)
	parsedIP := net.ParseIP(ip)
	if parsedIP == nil || parsedIP.To4() == nil {
		http.Error(w, "invalid TV IPv4 address", http.StatusBadRequest)
		return
	}
	if parsedIP.IsLoopback() || parsedIP.IsUnspecified() {
		http.Error(w, "TV IP cannot be 127.0.0.1/localhost; enter the TV LAN IP", http.StatusBadRequest)
		return
	}
	ip = parsedIP.To4().String()
	if err := src.startDLNACast(ip); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "target": ip, "url": src.dlnaURLValue()})
}

func handleDLNAStop(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	src.stopDLNACast()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

func handleDLNAStatus(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	st := src.dlnaStatusValue()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(st)
}

func (s *h264Source) dlnaStatusValue() dlnaStatus {
	s.dlnaMu.Lock()
	defer s.dlnaMu.Unlock()
	return dlnaStatus{Active: s.dlnaCancel != nil, Target: s.dlnaTargetIP, URL: s.dlnaURL}
}

func (s *h264Source) dlnaURLValue() string {
	s.dlnaMu.Lock()
	defer s.dlnaMu.Unlock()
	return s.dlnaURL
}

func (s *h264Source) startDLNACast(targetIP string) error {
	s.stopDLNACast()

	location, err := discoverDLNADevice(targetIP)
	if err != nil {
		return err
	}
	controlURL, err := fetchAVTransportControlURL(location)
	if err != nil {
		return err
	}

	gatewayIP := strings.TrimSpace(os.Getenv("DLNA_PUBLIC_IP"))
	if parsed := net.ParseIP(gatewayIP); parsed != nil && parsed.To4() != nil && !parsed.IsLoopback() && !parsed.IsUnspecified() {
		gatewayIP = parsed.To4().String()
	} else {
		gatewayIP = localIPFor(targetIP)
	}
	if gatewayIP == "" {
		return fmt.Errorf("cannot determine gateway LAN IP for TV %s; set DLNA_PUBLIC_IP to the gateway LAN IPv4 address", targetIP)
	}
	log.Printf("DLNA gateway media address selected: %s (target=%s)", gatewayIP, targetIP)
	listenHost := os.Getenv("DLNA_LISTEN_HOST")
	if listenHost == "" {
		listenHost = "0.0.0.0"
	}
	port := getenvInt("DLNA_PORT", 19001)
	mediaURL := "http://" + gatewayIP + ":" + strconv.Itoa(port) + dlnaStreamPath
	if err := s.ensureDLNAServer(listenHost, port); err != nil {
		return err
	}

	metadata := buildDLNAMetadata(mediaURL, s.headerSnapshot())
	if err := upnpSOAP(controlURL, "SetAVTransportURI", map[string]string{
		"InstanceID":         "0",
		"CurrentURI":         mediaURL,
		"CurrentURIMetaData": metadata,
	}); err != nil {
		return fmt.Errorf("DLNA SetAVTransportURI: %w", err)
	}
	if err := upnpSOAP(controlURL, "Play", map[string]string{
		"InstanceID": "0",
		"Speed":      "1",
	}); err != nil {
		return fmt.Errorf("DLNA Play: %w", err)
	}

	s.dlnaMu.Lock()
	ctx, cancel := context.WithCancel(context.Background())
	s.dlnaCancel = cancel
	s.dlnaTargetIP = targetIP
	s.dlnaURL = mediaURL
	s.dlnaMu.Unlock()
	_ = ctx
	log.Printf("DLNA cast started: target=%s location=%s media=%s", targetIP, location, mediaURL)
	return nil
}

func (s *h264Source) stopDLNACast() {
	s.dlnaMu.Lock()
	cancel := s.dlnaCancel
	s.dlnaCancel = nil
	s.dlnaTargetIP = ""
	s.dlnaURL = ""
	s.dlnaMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

var dlnaServerOnceMu sync.Mutex
var dlnaServerPort int
var dlnaServerHost string

func (s *h264Source) ensureDLNAServer(host string, port int) error {
	dlnaServerOnceMu.Lock()
	defer dlnaServerOnceMu.Unlock()
	if dlnaServerPort == port && dlnaServerHost == host {
		return nil
	}
	mux := http.NewServeMux()
	mux.HandleFunc(dlnaStreamPath, func(w http.ResponseWriter, r *http.Request) { s.serveDLNAStream(w, r) })
	mux.HandleFunc("/dlna/description.xml", func(w http.ResponseWriter, r *http.Request) { serveDLNADeviceDescription(w, r) })
	ln, err := net.Listen("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return fmt.Errorf("DLNA HTTP listen %s:%d: %w", host, port, err)
	}
	dlnaServerPort, dlnaServerHost = port, host
	go func() {
		if err := http.Serve(ln, mux); err != nil && !strings.Contains(err.Error(), "closed network connection") {
			log.Printf("DLNA HTTP server stopped: %v", err)
		}
	}()
	return nil
}

func (s *h264Source) headerSnapshot() streamHeader {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.header
}

func discoverDLNADevice(targetIP string) (string, error) {
	target := net.ParseIP(strings.TrimSpace(targetIP))
	if target == nil || target.To4() == nil {
		return "", fmt.Errorf("invalid renderer IP %q", targetIP)
	}
	targetIP = target.To4().String()
	if target.IsLoopback() || target.IsUnspecified() {
		return "", fmt.Errorf("invalid renderer IP %s: loopback/unspecified address is not a TV", targetIP)
	}

	// 1) Best effort: send M-SEARCH directly to the requested renderer.
	// Some TVs do not reliably answer multicast discovery, but will answer a
	// unicast M-SEARCH sent straight to their SSDP endpoint.
	for _, st := range []string{
		"urn:schemas-upnp-org:device:MediaRenderer:1",
		"urn:schemas-upnp-org:service:AVTransport:1",
		"ssdp:all",
	} {
		if loc := ssdpSearchTarget(targetIP, targetIP, st, 2*time.Second); loc != "" {
			return loc, nil
		}
	}

	// 2) Fall back to normal SSDP multicast, but only accept LOCATION URLs
	// whose hostname matches the user-specified renderer IP.
	if loc := ssdpSearchTarget(targetIP, "239.255.255.250", "urn:schemas-upnp-org:device:MediaRenderer:1", 2*time.Second); loc != "" {
		return loc, nil
	}
	if loc := ssdpSearchTarget(targetIP, "239.255.255.250", "ssdp:all", 2*time.Second); loc != "" {
		return loc, nil
	}

	// 3) Final fallback: many consumer TVs expose the UPnP root description
	// without a discoverable SSDP response. Probe a small set of common HTTP
	// ports/paths directly on the exact IP. The returned URL is then parsed for
	// AVTransport exactly like a normal SSDP LOCATION.
	for _, port := range []int{80, 8008, 8080, 8088, 8001, 8060, 9000, 9001, 49152, 49153, 5000, 52235, 7676, 3000, 7000} {
		for _, path := range []string{"/description.xml", "/rootDesc.xml", "/dd.xml", "/DeviceDescription.xml", "/upnp/desc.xml", "/upnp/rootDesc.xml", "/xml/device_description.xml", "/etc/linuxigd/gatedesc.xml"} {
			loc := fmt.Sprintf("http://%s:%d%s", targetIP, port, path)
			if ok := probeUPnPDescription(loc); ok {
				return loc, nil
			}
		}
	}
	return "", fmt.Errorf("DLNA renderer %s not reachable via targeted SSDP, multicast SSDP, or direct UPnP description probe", targetIP)
}

func ssdpSearchTarget(targetIP, destination, st string, timeout time.Duration) string {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return ""
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	msg := "M-SEARCH * HTTP/1.1\r\n" +
		"HOST: " + net.JoinHostPort(destination, "1900") + "\r\n" +
		"MAN: \"ssdp:discover\"\r\n" +
		"MX: 1\r\n" +
		"ST: " + st + "\r\n\r\n"
	if _, err := conn.WriteToUDP([]byte(msg), &net.UDPAddr{IP: net.ParseIP(destination).To4(), Port: 1900}); err != nil {
		return ""
	}
	buf := make([]byte, 8192)
	for {
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			return ""
		}
		headers := parseHTTPHeaders(buf[:n])
		loc := strings.TrimSpace(headers["location"])
		if loc == "" {
			continue
		}
		u, err := url.Parse(loc)
		if err != nil || !strings.EqualFold(u.Hostname(), targetIP) {
			continue
		}
		return loc
	}
}

func probeUPnPDescription(location string) bool {
	client := &http.Client{
		Timeout: 1200 * time.Millisecond,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 2 {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
	resp, err := client.Get(location)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return false
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 128*1024))
	if err != nil {
		return false
	}
	text := strings.ToLower(string(body))
	return strings.Contains(text, "avtransport") && strings.Contains(text, "upnp-org")
}

func fetchAVTransportControlURL(location string) (string, error) {
	client := &http.Client{Timeout: dlnaHTTPTimeout}
	resp, err := client.Get(location)
	if err != nil {
		return "", fmt.Errorf("fetch device description: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return "", fmt.Errorf("device description HTTP %s", resp.Status)
	}
	var desc upnpDeviceDescription
	if err := xml.NewDecoder(io.LimitReader(resp.Body, 512*1024)).Decode(&desc); err != nil {
		return "", fmt.Errorf("parse device description: %w", err)
	}
	var raw string
	for _, svc := range desc.Device.ServiceList.Services {
		if strings.Contains(strings.ToLower(svc.ServiceType), "avtransport") {
			raw = svc.ControlURL
			break
		}
	}
	if raw == "" {
		for _, dev := range desc.Device.Devices {
			for _, svc := range dev.ServiceList.Services {
				if strings.Contains(strings.ToLower(svc.ServiceType), "avtransport") {
					raw = svc.ControlURL
					break
				}
			}
		}
	}
	if raw == "" {
		return "", fmt.Errorf("AVTransport service not found")
	}
	base, err := url.Parse(location)
	if err != nil {
		return "", err
	}
	u, err := base.Parse(raw)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func upnpSOAP(endpoint, action string, args map[string]string) error {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="utf-8"?>`)
	b.WriteString(`<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>`)
	b.WriteString(`<u:` + action + ` xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">`)
	for _, k := range []string{"InstanceID", "CurrentURI", "CurrentURIMetaData", "Speed"} {
		if v, ok := args[k]; ok {
			b.WriteString("<" + k + ">" + xmlEscape(v) + "</" + k + ">")
		}
	}
	b.WriteString(`</u:` + action + `></s:Body></s:Envelope>`)
	req, err := http.NewRequest(http.MethodPost, endpoint, strings.NewReader(b.String()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", `text/xml; charset="utf-8"`)
	req.Header.Set("SOAPACTION", `"urn:schemas-upnp-org:service:AVTransport:1#`+action+`"`)
	resp, err := (&http.Client{Timeout: dlnaControlTimeout}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("HTTP %s: %s", resp.Status, strings.TrimSpace(string(body)))
	}
	return nil
}

func buildDLNAMetadata(mediaURL string, h streamHeader) string {
	name := "VirtualDisplay"
	res := fmt.Sprintf(`<res protocolInfo="http-get:*:video/mpegts:*" size="0" resolution="%dx%d">%s</res>`, max(1, h.Width), max(1, h.Height), xmlEscape(mediaURL))
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>%s</dc:title><upnp:class>object.item.videoItem</upnp:class>%s</item></DIDL-Lite>`, name, res)
}

func serveDLNADeviceDescription(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	_, _ = io.WriteString(w, `<?xml version="1.0" encoding="utf-8"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>VirtualDisplay Gateway</friendlyName><manufacturer>VirtualDisplay</manufacturer><modelName>VirtualDisplay DLNA Server</modelName><UDN>uuid:virtualdisplay-gateway</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><controlURL>/dlna/control/content</controlURL><eventSubURL>/dlna/event/content</eventSubURL><SCPDURL>/dlna/scpd/content.xml</SCPDURL></service></serviceList></device></root>`)
}

func countADTSFrames(data []byte) int {
	count := 0
	for i := 0; i < len(data)-7; {
		if data[i] == 0xFF && (data[i+1]&0xF0) == 0xF0 {
			frameLen := (int(data[i+3]&0x03) << 11) | (int(data[i+4]) << 3) | (int(data[i+5]) >> 5)
			if frameLen <= 0 {
				break
			}
			count++
			i += frameLen
		} else {
			i++
		}
	}
	return count
}

func (s *h264Source) peekVideoFrame(after uint64) *h264Frame {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.history) == 0 {
		return nil
	}

	// 当 after == 0 时，遍历历史，寻找【第一个关键帧】（避免因第一个帧是 P 帧而死锁返回 nil）
	if after == 0 {
		for _, f := range s.history {
			if f.flags&flagKeyFrame != 0 {
				return f
			}
		}
		return nil
	}

	// 已经锁定关键帧后，按 sequence 递增顺序吐帧
	for _, f := range s.history {
		if f.seq > after {
			return f
		}
	}
	return nil
}

func (s *h264Source) peekAudioFrame(after uint64) *audioFrame {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, af := range s.audioHistory {
		if af.seq > after {
			return af
		}
	}
	return nil
}

// 5. 核心 HTTP 服务推流逻辑
func (s *h264Source) serveDLNAStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	h := s.headerSnapshot()
	if h.Width <= 0 || h.Height <= 0 {
		h.Width, h.Height = 1920, 1080
	}
	w.Header().Set("Content-Type", "video/mpegts")
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	w.Header().Set("Connection", "keep-alive")
	if r.Method == http.MethodHead {
		return
	}

	flusher, _ := w.(http.Flusher)
	enc := newMPEGTSWriter(w, uint16(h.Width), uint16(h.Height))
	if err := enc.writePATPMT(); err != nil {
		return
	}
	flusherSafeFlush(flusher)

	var lastVideoSeq uint64
	var lastAudioSeq uint64

	transcoder := NewAudioTranscoder(48000, 2, 96000)
	if transcoder == nil {
		log.Printf("[DLNA-AUDIO] FDK AAC transcoder initialization failed")
	} else {
		defer transcoder.Close()
	}

	// IMPORTANT: scrcpy video/audio PTS are not guaranteed to share the same
	// origin. WebRTC deliberately normalizes video PTS, while ROLE_AUDIO exposes
	// the source audio clock. Using the two raw PTS values to interleave or to
	// start the TS program can put AAC hours away from the video timeline.
	//
	// Establish one local 90 kHz timeline. Keep the pacing of each source stream,
	// but align their zero points by the arrival time of the first cached
	// video/audio frames.
	var videoBasePtsUs int64 = -1
	var audioBasePtsUs int64 = -1
	var audioOffsetUs int64
	timelineReady := false
	var lastWrittenVideoPts uint64
	var lastWrittenAudioPts uint64

	// The audio history is intentionally buffered for WebRTC reconnects. A new
	// DLNA client must NOT start from the oldest retained Opus packet, otherwise
	// it can begin several seconds behind the first video keyframe. We seek the
	// audio cursor to the frame whose receive timestamp is closest to the first
	// video keyframe instead.	
	var audioStartReceivedAt time.Time

	for {
		vf := s.peekVideoFrame(lastVideoSeq)
		af := s.peekAudioFrame(lastAudioSeq)

		if vf == nil && af == nil {
			s.mu.RLock()
			chV := s.changed
			chA := s.audioChanged
			s.mu.RUnlock()
			select {
			case <-r.Context().Done():
				return
			case <-chV:
			case <-chA:
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}

		// Do not put audio before the first video keyframe. But once the first
		// video frame is available, establish both stream bases from the same
		// cached-history snapshot and retain the audio/video pacing thereafter.
		if !timelineReady {
			if vf == nil || vf.flags&flagKeyFrame == 0 {
				time.Sleep(2 * time.Millisecond)
				continue
			}

			// Re-seek audio once, using receive time rather than source PTS. This
			// removes the accumulated history backlog from the DLNA start point.
			if audioStartReceivedAt.IsZero() {
				if vf.receivedAt.IsZero() {
					time.Sleep(2 * time.Millisecond)
					continue
				}
				s.mu.RLock()
				var nearest *audioFrame
				var nearestDelta time.Duration
				for _, candidate := range s.audioHistory {
					if candidate.receivedAt.IsZero() {
						continue
					}
					d := candidate.receivedAt.Sub(vf.receivedAt)
					if d < 0 {
						d = -d
					}
					if nearest == nil || d < nearestDelta {
						nearest = candidate
						nearestDelta = d
					}
				}
				s.mu.RUnlock()

				if nearest == nil {
					time.Sleep(2 * time.Millisecond)
					continue
				}
				// peekAudioFrame(after) returns seq > after, so seq-1 makes
				// 'nearest' the first audio frame emitted by this client.
				if nearest.seq > 0 {
					lastAudioSeq = nearest.seq - 1
				}
				audioStartReceivedAt = nearest.receivedAt
				log.Printf("[DLNA-AUDIO] start seek videoRecv=%s audioRecv=%s backlog=%s audioSeq=%d",
					vf.receivedAt.Format(time.RFC3339Nano), nearest.receivedAt.Format(time.RFC3339Nano),
					nearest.receivedAt.Sub(vf.receivedAt), nearest.seq)
			}

			af = s.peekAudioFrame(lastAudioSeq)
			if af == nil {
				time.Sleep(2 * time.Millisecond)
				continue
			}

			videoBasePtsUs = vf.ptsUs
			audioBasePtsUs = af.ptsUs
			// Fine-tune the local audio clock from receive-time skew. Positive
			// skew means the audio packet arrived later and therefore needs to
			// start slightly earlier on the common timeline, not later.
			if !vf.receivedAt.IsZero() && !af.receivedAt.IsZero() {
				audioOffsetUs = -af.receivedAt.Sub(vf.receivedAt).Microseconds()
			}
			if audioOffsetUs < -500_000 || audioOffsetUs > 500_000 {
				audioOffsetUs = 0
			}
			timelineReady = true
			log.Printf("[DLNA-AUDIO] timeline aligned videoPTS=%d audioPTS=%d audioOffset=%dus",
				videoBasePtsUs, audioBasePtsUs, audioOffsetUs)
		}

		writeVideo := false
		writeAudio := false
		var vLocalUs, aLocalUs int64

		if vf != nil {
			vLocalUs = vf.ptsUs - videoBasePtsUs
			if vLocalUs < 0 {
				vLocalUs = 0
			}
		}
		if af != nil {
			aLocalUs = af.ptsUs - audioBasePtsUs + audioOffsetUs
			if aLocalUs < 0 {
				aLocalUs = 0
			}
		}

		if vf != nil && af != nil {
			if vLocalUs <= aLocalUs {
				writeVideo = true
			} else {
				writeAudio = true
			}
		} else if vf != nil {
			writeVideo = true
		} else if af != nil {
			writeAudio = true
		}

		if writeVideo {
			data := vf.data
			if vf.flags&flagKeyFrame != 0 {
				_ = enc.writePATPMT()
				if !containsSPSPPS(data) {
					s.mu.RLock()
					cfg := append([]byte(nil), s.config...)
					s.mu.RUnlock()
					if len(cfg) > 0 {
						data = append(cfg, data...)
					}
				}
			}
			pts90k := uint64(vLocalUs) * 90 / 1000
			// Guard against accidental backward PTS after a source reconnect.
			if pts90k < lastWrittenVideoPts {
				pts90k = lastWrittenVideoPts
			}
			if err := enc.writeH264AccessUnit(data, pts90k); err != nil {
				return
			}
			lastWrittenVideoPts = pts90k
			lastVideoSeq = vf.seq
		} else if writeAudio {
			var aacData []byte
			if transcoder != nil {
				aacData = transcoder.Transcode(af.data)
			}
			if len(aacData) == 0 {
				lastAudioSeq = af.seq
				flusherSafeFlush(flusher)
				continue
			}

			adtsFrames := countADTSFrames(aacData)
			if adtsFrames <= 0 {
				log.Printf("[DLNA-AUDIO] encoder returned non-ADTS data size=%d first=%s",
					len(aacData), firstBytes(aacData, 16))
				lastAudioSeq = af.seq
				flusherSafeFlush(flusher)
				continue
			}

			pts90k := uint64(aLocalUs) * 90 / 1000
			if pts90k < lastWrittenAudioPts {
				pts90k = lastWrittenAudioPts
			}
			if err := enc.writeAACFrame(aacData, pts90k); err != nil {
				return
			}
			// AAC-LC/ADTS uses 1024 samples per AAC frame. At 48 kHz:
			// 1024 / 48000 * 90000 = 1920 ticks.
			lastWrittenAudioPts = pts90k + uint64(adtsFrames)*1920

			if s.audioFrames <= 10 || s.audioFrames%250 == 0 {
				log.Printf("[DLNA-AUDIO] AAC output: opusSeq=%d adtsFrames=%d bytes=%d pts90k=%d",
					af.seq, adtsFrames, len(aacData), pts90k)
			}
			lastAudioSeq = af.seq
		}

		flusherSafeFlush(flusher)
	}
}

func (s *h264Source) nextFrame(after uint64, timeout time.Duration) (*h264Frame, bool) {
	deadline := time.Now().Add(timeout)
	for {
		s.mu.RLock()
		i := sort.Search(len(s.history), func(i int) bool { return s.history[i].seq > after })
		if i < len(s.history) {
			f := s.history[i]
			if after != 0 || f.flags&flagKeyFrame != 0 {
				s.mu.RUnlock()
				return f, true
			}
		}
		ch := s.changed
		s.mu.RUnlock()
		remain := time.Until(deadline)
		if remain <= 0 {
			return nil, false
		}
		t := time.NewTimer(minDuration(remain, 500*time.Millisecond))
		select {
		case <-ch:
			if !t.Stop() {
				select {
				case <-t.C:
				default:
				}
			}
		case <-t.C:
			return nil, false
		}
	}
}

type mpegTSWriter struct {
	w       io.Writer
	ccVideo uint8
	ccAudio uint8
}

func newMPEGTSWriter(w io.Writer, _, _ uint16) *mpegTSWriter {
	return &mpegTSWriter{w: w}
}

func (m *mpegTSWriter) writePATPMT() error {
	pat := buildPATPacket()
	if _, err := m.w.Write(pat[:]); err != nil {
		return err
	}
	pmt := buildPMTPacket()
	if _, err := m.w.Write(pmt[:]); err != nil {
		return err
	}
	return nil
}

func (m *mpegTSWriter) writeH264AccessUnit(annexBData []byte, pts90k uint64) error {
	pes := buildPES(0xe0, annexBData, pts90k)
	return m.writePES(tsPIDVideo, pes, true, pts90k)
}

func (m *mpegTSWriter) writeOpusFrame(opusData []byte, pts90k uint64) error {
	pes := buildPES(0xc0, opusData, pts90k)
	return m.writePES(tsPIDAudio, pes, false, pts90k)
}

func (m *mpegTSWriter) writeAACFrame(aacData []byte, pts90k uint64) error {
	pes := buildPES(0xc0, aacData, pts90k) // 0xc0 为 MPEG Audio 标志
	return m.writePES(tsPIDAudio, pes, false, pts90k)
}

func (m *mpegTSWriter) writePES(pid uint16, pes []byte, randomAccess bool, pts90k uint64) error {
	var cc *uint8
	if pid == tsPIDAudio {
		cc = &m.ccAudio
	} else {
		cc = &m.ccVideo
	}

	first := true
	for len(pes) > 0 {
		var pkt [188]byte
		pkt[0] = 0x47
		pkt[1] = byte((pid >> 8) & 0x1f)
		pkt[2] = byte(pid)

		includePCR := first && (pid == tsPIDVideo)
		var payloadCapacity int
		var adaptationLen int

		// 核心修复：精准计算每个包的实际 Payload 容量与 Adaptation 填充长度
		if includePCR {
			payloadCapacity = 176
			if len(pes) < payloadCapacity {
				payloadCapacity = len(pes)
			}
			adaptationLen = 183 - payloadCapacity
			if adaptationLen < 7 {
				adaptationLen = 7
			}
		} else {
			if len(pes) < 184 {
				payloadCapacity = len(pes) // 修复点：必须更新 payloadCapacity 为剩余字节数
				adaptationLen = 183 - payloadCapacity
			} else {
				payloadCapacity = 184
				adaptationLen = -1
			}
		}

		payloadStart := 4
		if first {
			pkt[1] |= 0x40 // payload_unit_start_indicator
		}

		if adaptationLen >= 0 {
			pkt[3] = 0x30 | (*cc & 0x0f)
			*cc = (*cc + 1) & 0x0f
			pkt[4] = byte(adaptationLen)

			if adaptationLen > 0 {
				flags := byte(0)
				if first && randomAccess {
					flags |= 0x40 // random_access_indicator
				}
				if includePCR {
					flags |= 0x10 // PCR_flag
				}
				pkt[5] = flags
				pos := 6
				if includePCR {
					writePCR(pkt[pos:pos+6], pts90k)
					pos += 6
				}
				for pos < 5+adaptationLen {
					pkt[pos] = 0xff
					pos++
				}
			}
			payloadStart = 5 + adaptationLen
		} else {
			pkt[3] = 0x10 | (*cc & 0x0f)
			*cc = (*cc + 1) & 0x0f
		}

		// 安全安全复制数据，不再引发 Panic
		copy(pkt[payloadStart:], pes[:payloadCapacity])
		for i := payloadStart + payloadCapacity; i < 188; i++ {
			pkt[i] = 0xff
		}

		if _, err := m.w.Write(pkt[:]); err != nil {
			return err
		}
		pes = pes[payloadCapacity:]
		first = false
	}
	return nil
}

func buildPES(streamID byte, payload []byte, pts90k uint64) []byte {
	pes := make([]byte, 14+len(payload))
	copy(pes[0:4], []byte{0, 0, 1, streamID})
	packetLen := len(payload) + 8
	if packetLen > 0xffff {
		packetLen = 0
	}
	binary.BigEndian.PutUint16(pes[4:6], uint16(packetLen))
	pes[6] = 0x80
	pes[7] = 0x80
	pes[8] = 0x05
	writePTS(pes[9:14], pts90k)
	copy(pes[14:], payload)
	return pes
}

func writePTS(dst []byte, pts uint64) {
	pts &= (1 << 33) - 1
	dst[0] = byte(0x21 | ((pts >> 29) & 0x0e))
	dst[1] = byte(pts >> 22)
	dst[2] = byte(0x01 | ((pts >> 14) & 0xfe))
	dst[3] = byte(pts >> 7)
	dst[4] = byte(0x01 | ((pts << 1) & 0xfe))
}

func writePCR(dst []byte, pts90k uint64) {
	base := pts90k & ((1 << 33) - 1)
	ext := uint64(0)
	dst[0] = byte(base >> 25)
	dst[1] = byte(base >> 17)
	dst[2] = byte(base >> 9)
	dst[3] = byte(base >> 1)
	dst[4] = byte((base&1)<<7 | 0x7e | (ext >> 8))
	dst[5] = byte(ext)
}

// 构建 PAT (Program Association Table)
func buildPATPacket() [188]byte {
	var p [188]byte
	p[0] = 0x47
	p[1] = 0x40
	p[2] = 0x00
	p[3] = 0x10
	p[4] = 0x00

	section := []byte{
		0x00, 0xb0, 0x0d, 0x00, 0x01, 0xc1, 0x00, 0x00,
		0x00, 0x01, byte((tsPIDPMT>>8)&0x1f) | 0xe0, byte(tsPIDPMT & 0xff),
	}
	copy(p[5:], section)
	binary.BigEndian.PutUint32(p[5+len(section):5+len(section)+4], mpegCRC32(section))
	for i := 5 + len(section) + 4; i < len(p); i++ {
		p[i] = 0xff
	}
	return p
}

func buildPMTPacket() [188]byte {
	var p [188]byte
	p[0] = 0x47
	p[1] = 0x50 | byte((tsPIDPMT>>8)&0x1f)
	p[2] = byte(tsPIDPMT & 0xff)
	p[3] = 0x10
	p[4] = 0x00

	// Section length 改为 0x17 (23 bytes)
	section := []byte{
		0x02, 0xb0, 0x17, 0x00, 0x01, 0xc1, 0x00, 0x00,
		0xe1, 0x00, 0xf0, 0x00,
		// Video ES (H.264, PID 0x0100)
		0x1b, byte(0xe0 | ((tsPIDVideo >> 8) & 0x1f)), byte(tsPIDVideo & 0xff), 0xf0, 0x00,
		// Audio ES (修改为 ADTS AAC 0x0f, PID 0x0101)
		0x0f, byte(0xe0 | ((tsPIDAudio >> 8) & 0x1f)), byte(tsPIDAudio & 0xff), 0xf0, 0x00,
	}
	copy(p[5:], section)
	binary.BigEndian.PutUint32(p[5+len(section):5+len(section)+4], mpegCRC32(section))
	for i := 5 + len(section) + 4; i < len(p); i++ {
		p[i] = 0xff
	}
	return p
}

func mpegCRC32(data []byte) uint32 {
	crc := uint32(0xffffffff)
	for _, b := range data {
		for i := 7; i >= 0; i-- {
			bit := (b >> i) & 1
			c15 := (crc >> 31) & 1
			crc <<= 1
			if c15^uint32(bit) != 0 {
				crc ^= 0x04c11db7
			}
		}
	}
	return crc
}

func parseHTTPHeaders(b []byte) map[string]string {
	out := map[string]string{}
	lines := strings.Split(string(b), "\r\n")
	for _, line := range lines[1:] {
		p := strings.SplitN(line, ":", 2)
		if len(p) == 2 {
			out[strings.ToLower(strings.TrimSpace(p[0]))] = strings.TrimSpace(p[1])
		}
	}
	return out
}

func localIPFor(targetIP string) string {
	target := net.ParseIP(strings.TrimSpace(targetIP))
	if target == nil || target.To4() == nil || target.IsLoopback() || target.IsUnspecified() {
		return ""
	}
	c, err := net.DialTimeout("udp4", net.JoinHostPort(target.To4().String(), "1900"), time.Second)
	if err == nil {
		defer c.Close()
		if addr, ok := c.LocalAddr().(*net.UDPAddr); ok && addr != nil && addr.IP != nil && !addr.IP.IsLoopback() && !addr.IP.IsUnspecified() {
			if ip := addr.IP.To4(); ip != nil {
				return ip.String()
			}
		}
	}
	// Fallback for hosts where UDP connect cannot establish a route before the
	// TV is reachable: choose a non-loopback IPv4 interface on the same subnet.
	t4 := target.To4()
	interfaces, _ := net.Interfaces()
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			var ipnet *net.IPNet
			switch v := a.(type) {
			case *net.IPNet:
				ipnet = v
			case *net.IPAddr:
				ipnet = &net.IPNet{IP: v.IP, Mask: net.CIDRMask(24, 32)}
			}
			if ipnet == nil || ipnet.IP == nil {
				continue
			}
			ip := ipnet.IP.To4()
			if ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
				continue
			}
			if ipnet.Contains(t4) {
				return ip.String()
			}
		}
	}
	return ""
}

func xmlEscape(s string) string {
	var b strings.Builder
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}

func getenvInt(k string, d int) int {
	v := strings.TrimSpace(os.Getenv(k))
	if v == "" {
		return d
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return d
	}
	return n
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
func flusherSafeFlush(f http.Flusher) {
	if f != nil {
		f.Flush()
	}
}

// ---- daemon protocol helpers ----

type roleEntry struct {
	role      int
	displayID int32
}

func configureTCP(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetReadBuffer(1024 * 1024)
		_ = tc.SetWriteBuffer(1024 * 1024)
		_ = tc.SetKeepAlive(true)
	}
}

func writeU8(c net.Conn, v int) error {
	_, err := c.Write([]byte{byte(v)})
	return err
}

func writeI32(c net.Conn, v int32) error {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(v))
	_, err := c.Write(b[:])
	return err
}

func writeU32(c net.Conn, v uint32) error {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	_, err := c.Write(b[:])
	return err
}

func writeU64(c net.Conn, v uint64) error {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	_, err := c.Write(b[:])
	return err
}

func writeString32(c net.Conn, v string) error {
	b := []byte(v)
	if err := writeI32(c, int32(len(b))); err != nil {
		return err
	}
	_, err := c.Write(b)
	return err
}

func writeConfigureSession(c net.Conn, seq int64, options string, entries []roleEntry) error {
	if err := writeU8(c, typeConfigureSession); err != nil {
		return err
	}
	if err := writeU64(c, uint64(seq)); err != nil {
		return err
	}
	if err := writeString32(c, options); err != nil {
		return err
	}
	if err := writeI32(c, 0); err != nil { // legacy rolesMask: unused when entries are present
		return err
	}
	if err := writeI32(c, int32(len(entries))); err != nil {
		return err
	}
	for _, e := range entries {
		if err := writeU8(c, e.role); err != nil {
			return err
		}
		if err := writeI32(c, e.displayID); err != nil {
			return err
		}
	}
	return nil
}

type genericResponse struct {
	sequence   int64
	statusCode int32
	displayID  int32
	message    string
}

func readGenericResponse(r io.Reader) (genericResponse, error) {
	typ, err := readU8(r)
	if err != nil {
		return genericResponse{}, err
	}
	if typ != typeGenericResponse {
		return genericResponse{}, fmt.Errorf("unexpected daemon response type=%d", typ)
	}
	seq, err := readI64(r)
	if err != nil {
		return genericResponse{}, err
	}
	status, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	display, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	n, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	if n < 0 || n > 1<<20 {
		return genericResponse{}, fmt.Errorf("invalid daemon response message length=%d", n)
	}
	b := make([]byte, int(n))
	if _, err := io.ReadFull(r, b); err != nil {
		return genericResponse{}, err
	}
	return genericResponse{
		sequence:   seq,
		statusCode: status,
		displayID:  display,
		message:    string(b),
	}, nil
}

func readU8(r io.Reader) (byte, error) {
	var b [1]byte
	_, err := io.ReadFull(r, b[:])
	return b[0], err
}

func readI32From4(b []byte) (int32, error) {
	if len(b) != 4 {
		return 0, fmt.Errorf("expected exactly 4 bytes, got %d", len(b))
	}
	return int32(binary.BigEndian.Uint32(b)), nil
}

func readI32(r io.Reader) (int32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int32(binary.BigEndian.Uint32(b[:])), nil
}

func readU32(r io.Reader) (uint32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(b[:]), nil
}

func readU64(r io.Reader) (uint64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(b[:]), nil
}

func readI64(r io.Reader) (int64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int64(binary.BigEndian.Uint64(b[:])), nil
}

func trimCString(b []byte) string {
	if i := bytes.IndexByte(b, 0); i >= 0 {
		b = b[:i]
	}
	return string(b)
}

type rawRGBAFrame struct {
	ptsUs  int64
	width  int
	height int
	data   []byte
}

func (s *h264Source) addTilePeer(dc *webrtc.DataChannel) {
	s.rawStartOnce.Do(func() { go s.runRawTileStream(rawFrameDefaultAddr) })
	s.tileMu.Lock()
	s.tilePeers[dc] = struct{}{}
	s.tileReady = false
	s.tileMu.Unlock()

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if !msg.IsString {
			return
		}
		var m struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(msg.Data, &m) != nil || m.Type != "base-ready" {
			return
		}
		s.tileMu.Lock()
		if _, ok := s.tilePeers[dc]; ok {
			s.tileReady = true
		}
		s.tileMu.Unlock()
		log.Printf("[TILE] browser base-ready displayId=%d", s.displayID)
	})
}

func (s *h264Source) removeTilePeer(dc *webrtc.DataChannel) {
	s.tileMu.Lock()
	delete(s.tilePeers, dc)
	if len(s.tilePeers) == 0 {
		s.tileReady = false
	}
	s.tileMu.Unlock()
}

func (s *h264Source) setTileMode(enabled bool, reason string) {
	s.tileMu.Lock()
	changed := s.tileMode != enabled
	s.tileMode = enabled
	s.tileReady = false
	s.tileLowCount = 0
	peers := make([]*webrtc.DataChannel, 0, len(s.tilePeers))
	for dc := range s.tilePeers {
		peers = append(peers, dc)
	}
	s.tileMu.Unlock()
	if !changed {
		return
	}
	// Any tile/full transition must re-base the H264 decoder. Tile mode needs a
	// fresh base frame; full mode needs a fresh IDR because predictive frames
	// skipped during tile mode cannot be used as a reference on the browser.
	s.mu.Lock()
	s.keyRequest++
	s.mu.Unlock()
	mode := "full"
	if enabled {
		mode = "tile-pending"
	}
	msg := fmt.Sprintf(`{"type":"mode","mode":"%s"}`, mode)
	for _, dc := range peers {
		if err := dc.SendText(msg); err != nil {
			log.Printf("[TILE] mode send failed: %v", err)
		}
	}
	log.Printf("[TILE] mode=%s reason=%s", mode, reason)
}

func (s *h264Source) isTileMode() bool {
	s.tileMu.RLock()
	v := s.tileMode && len(s.tilePeers) > 0
	s.tileMu.RUnlock()
	return v
}

func (s *h264Source) isTileOutputReady() bool {
	s.tileMu.RLock()
	v := s.tileMode && s.tileReady && len(s.tilePeers) > 0
	s.tileMu.RUnlock()
	return v
}

func (s *h264Source) runRawTileStream(addr string) {
	for {
		s.shmMu.RLock()
		shm := s.shm
		s.shmMu.RUnlock()
		if shm == nil || shm.raw == nil {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		log.Printf("[TILE] raw-frame SHM source connected displayId=%d", s.displayID)
		if err := s.consumeRawShmFrames(shm.raw); err != nil {
			log.Printf("[TILE] raw-frame SHM source stopped: %v", err)
		}
		s.tileMu.Lock()
		s.rawLastError = ""
		s.tileMu.Unlock()
		s.setTileMode(false, "raw-shm-source-disconnected")
		time.Sleep(200 * time.Millisecond)
	}
}

func (s *h264Source) consumeRawShmFrames(r *shmMappedRing) error {
	var previous []byte
	var want uint64 = 1
	lowCount := 0
	tileSynced := false
	for {
		pts, flags, w, h, data, ok, overrun := r.readSeq(want)
		if overrun {
			latest := r.latestSeq()
			want = latest + 1
			previous = nil
			lowCount = 0
			tileSynced = false
			continue
		}
		if !ok {
			time.Sleep(2 * time.Millisecond)
			continue
		}
		_ = flags
		if w <= 0 || h <= 0 || len(data) != w*h*4 {
			return fmt.Errorf("invalid SHM raw frame %dx%d bytes=%d", w, h, len(data))
		}
		frame := rawRGBAFrame{ptsUs: pts, width: w, height: h, data: data}
		peers := s.tilePeersSnapshot()
		hasPeers := len(peers) > 0
		scrollDy, scrollScore, scrollOK := 0, 0.0, false
		if hasPeers && previous != nil {
			scrollDy, scrollScore, scrollOK = detectScrollDelta(previous, data, w, h)
		}

		changed, total := compareRawTiles(previous, &frame)
		ratio := 1.0
		if total > 0 {
			ratio = float64(changed) / float64(total)
		}

		if !s.isTileMode() {
			if hasPeers && scrollOK {
				s.setTileMode(true, fmt.Sprintf("scroll dy=%d score=%.2f", scrollDy, scrollScore))
				lowCount = 0
			} else if hasPeers && ratio <= 0.12 {
				lowCount++
				if lowCount >= 4 {
					s.setTileMode(true, fmt.Sprintf("changed=%.2f", ratio))
					lowCount = 0
				}
			} else {
				lowCount = 0
			}
		} else if !scrollOK && ratio >= 0.45 {
			s.setTileMode(false, fmt.Sprintf("changed=%.2f", ratio))
			lowCount = 0
		}

		if s.isTileMode() {
			if !s.isTileOutputReady() {
				previous = append(previous[:0], data...)
				tileSynced = false
				want++
				continue
			}
			if !tileSynced {
				previous = append(previous[:0], data...)
				tileSynced = true
				want++
				continue
			}
			if scrollOK {
				s.sendScrollDelta(scrollDy)
				for _, tile := range changedTilesAfterScroll(previous, &frame, scrollDy) {
					_ = s.sendTile(frame.data, frame.width, frame.height, tile.x, tile.y, tile.w, tile.h, frame.ptsUs)
				}
			} else {
				for _, tile := range changedTiles(previous, &frame) {
					_ = s.sendTile(frame.data, frame.width, frame.height, tile.x, tile.y, tile.w, tile.h, frame.ptsUs)
				}
			}
		}
		previous = append(previous[:0], data...)
		want++
	}
}

func (s *h264Source) consumeRawFrames(br *bufio.Reader) error {
	var hdr [24]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil {
		return fmt.Errorf("raw header: %w", err)
	}
	if binary.BigEndian.Uint32(hdr[0:4]) != rawFrameMagic {
		return fmt.Errorf("bad raw magic")
	}
	if binary.BigEndian.Uint32(hdr[4:8]) != rawFrameProtocolVersion {
		return fmt.Errorf("unsupported raw protocol")
	}
	w := int(binary.BigEndian.Uint32(hdr[8:12]))
	h := int(binary.BigEndian.Uint32(hdr[12:16]))
	if w <= 0 || h <= 0 || w > 8192 || h > 8192 {
		return fmt.Errorf("invalid raw size %dx%d", w, h)
	}
	s.tileMu.Lock()
	s.rawWidth = w
	s.rawHeight = h
	s.tileMu.Unlock()

	var previous []byte
	tileSynced := false
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			return fmt.Errorf("raw frame header: %w", err)
		}
		if binary.BigEndian.Uint32(hdr[0:4]) != rawFrameRawMagic {
			return fmt.Errorf("bad raw frame magic")
		}
		pts := int64(binary.BigEndian.Uint64(hdr[4:12]))
		fw := int(binary.BigEndian.Uint32(hdr[12:16]))
		fh := int(binary.BigEndian.Uint32(hdr[16:20]))
		n := int(binary.BigEndian.Uint32(hdr[20:24]))
		if fw != w || fh != h || n != fw*fh*4 {
			return fmt.Errorf("invalid raw frame %dx%d bytes=%d", fw, fh, n)
		}
		data := make([]byte, n)
		if _, err := io.ReadFull(br, data); err != nil {
			return fmt.Errorf("raw frame payload: %w", err)
		}
		frame := rawRGBAFrame{ptsUs: pts, width: fw, height: fh, data: data}
		peers := s.tilePeersSnapshot()
		hasPeers := len(peers) > 0
		scrollDy, scrollScore, scrollOK := 0, 0.0, false
		if hasPeers && previous != nil {
			scrollDy, scrollScore, scrollOK = detectScrollDelta(previous, data, fw, fh)
		}
		changed, total := compareRawTiles(previous, &frame)
		ratio := 1.0
		if total > 0 {
			ratio = float64(changed) / float64(total)
		}
		if !s.isTileMode() {
			if hasPeers && scrollOK {
				s.setTileMode(true, fmt.Sprintf("scroll dy=%d score=%.2f", scrollDy, scrollScore))
			} else if hasPeers && ratio <= 0.12 {
				s.setTileMode(true, fmt.Sprintf("changed=%.2f", ratio))
			}
		} else if !scrollOK && ratio >= 0.45 {
			s.setTileMode(false, fmt.Sprintf("changed=%.2f", ratio))
		}
		if s.isTileMode() {
			if !s.isTileOutputReady() {
				previous = append(previous[:0], data...)
				tileSynced = false
				continue
			}
			if !tileSynced {
				previous = append(previous[:0], data...)
				tileSynced = true
				continue
			}
			if scrollOK {
				s.sendScrollDelta(scrollDy)
				for _, tile := range changedTilesAfterScroll(previous, &frame, scrollDy) {
					_ = s.sendTile(frame.data, frame.width, frame.height, tile.x, tile.y, tile.w, tile.h, frame.ptsUs)
				}
			} else {
				for _, tile := range changedTiles(previous, &frame) {
					_ = s.sendTile(frame.data, frame.width, frame.height, tile.x, tile.y, tile.w, tile.h, frame.ptsUs)
				}
			}
		}
		previous = append(previous[:0], data...)
	}
}

func (s *h264Source) tilePeersSnapshot() []*webrtc.DataChannel {
	s.tileMu.RLock()
	defer s.tileMu.RUnlock()
	out := make([]*webrtc.DataChannel, 0, len(s.tilePeers))
	for dc := range s.tilePeers {
		out = append(out, dc)
	}
	return out
}

type rawTile struct{ x, y, w, h int }

func compareRawTiles(previous []byte, frame *rawRGBAFrame) (int, int) {
	tiles := 0
	changed := 0
	for y := 0; y < frame.height; y += rawFrameTileSize {
		for x := 0; x < frame.width; x += rawFrameTileSize {
			tiles++
			if previous == nil || rawTileChanged(previous, frame.data, frame.width, frame.height, x, y, minInt(rawFrameTileSize, frame.width-x), minInt(rawFrameTileSize, frame.height-y)) {
				changed++
			}
		}
	}
	return changed, tiles
}

func changedTiles(previous []byte, frame *rawRGBAFrame) []rawTile {
	out := make([]rawTile, 0, 16)
	for y := 0; y < frame.height; y += rawFrameTileSize {
		for x := 0; x < frame.width; x += rawFrameTileSize {
			w := minInt(rawFrameTileSize, frame.width-x)
			h := minInt(rawFrameTileSize, frame.height-y)
			if previous == nil || rawTileChanged(previous, frame.data, frame.width, frame.height, x, y, w, h) {
				out = append(out, rawTile{x, y, w, h})
			}
		}
	}
	return out
}

func detectScrollDelta(previous, current []byte, width, height int) (int, float64, bool) {
	if len(previous) != len(current) || width < 2 || height < 2 {
		return 0, 0, false
	}
	base := scrollMatchScore(previous, current, width, height, 0)
	bestDy := 0
	best := base
	for dy := -160; dy <= 160; dy += 4 {
		if dy == 0 {
			continue
		}
		score := scrollMatchScore(previous, current, width, height, dy)
		if score > best {
			best = score
			bestDy = dy
		}
	}
	if bestDy == 0 || best < 0.48 || best-base < 0.10 {
		return 0, best, false
	}
	return bestDy, best, true
}

func scrollMatchScore(previous, current []byte, width, height, shiftY int) float64 {
	y0, y1 := 0, height
	if shiftY > 0 {
		y0 = shiftY
	} else if shiftY < 0 {
		y1 = height + shiftY
	}
	if y0 >= y1 {
		return 0
	}
	matches := 0
	total := 0
	for y := y0; y < y1; y += 16 {
		prevY := y - shiftY
		for x := 0; x < width; x += 16 {
			pi := (prevY*width + x) * 4
			ci := (y*width + x) * 4
			if pi < 0 || ci < 0 || pi+3 >= len(previous) || ci+3 >= len(current) {
				continue
			}
			total++
			if previous[pi] == current[ci] && previous[pi+1] == current[ci+1] &&
				previous[pi+2] == current[ci+2] && previous[pi+3] == current[ci+3] {
				matches++
			}
		}
	}
	if total == 0 {
		return 0
	}
	return float64(matches) / float64(total)
}

func changedTilesAfterScroll(previous []byte, frame *rawRGBAFrame, shiftY int) []rawTile {
	out := make([]rawTile, 0, 16)
	for y := 0; y < frame.height; y += rawFrameTileSize {
		for x := 0; x < frame.width; x += rawFrameTileSize {
			w := minInt(rawFrameTileSize, frame.width-x)
			h := minInt(rawFrameTileSize, frame.height-y)
			if !rawTileEquivalentAtShift(previous, frame.data, frame.width, frame.height, x, y, w, h, shiftY) {
				out = append(out, rawTile{x, y, w, h})
			}
		}
	}
	return out
}

func rawTileEquivalentAtShift(a, b []byte, width, height, x, y, w, h, shiftY int) bool {
	prevY := y - shiftY
	if prevY < 0 || prevY+h > height {
		return false
	}
	matches := 0
	total := 0
	for yy := 0; yy < h; yy += 8 {
		for xx := 0; xx < w; xx += 8 {
			pi := ((prevY+yy)*width + x + xx) * 4
			ci := ((y+yy)*width + x + xx) * 4
			if pi < 0 || ci < 0 || pi+3 >= len(a) || ci+3 >= len(b) {
				continue
			}
			total++
			if a[pi] == b[ci] && a[pi+1] == b[ci+1] && a[pi+2] == b[ci+2] && a[pi+3] == b[ci+3] {
				matches++
			}
		}
	}
	if total == 0 {
		return false
	}
	return float64(matches)/float64(total) >= 0.82
}

func rawTileChanged(a, b []byte, width, height, x, y, w, h int) bool {
	// Fast sampled FNV-1a; sampling one pixel in each 4x4 block is enough to
	// detect normal UI changes while keeping the gateway-side compare cheap.
	var ha uint64 = 1469598103934665603
	var hb uint64 = 1469598103934665603
	for yy := y; yy < y+h; yy += 4 {
		base := yy * width * 4
		for xx := x; xx < x+w; xx += 4 {
			i := base + xx*4
			for k := 0; k < 4; k++ {
				ha ^= uint64(a[i+k])
				ha *= 1099511628211
				hb ^= uint64(b[i+k])
				hb *= 1099511628211
			}
		}
	}
	return ha != hb
}

func (s *h264Source) sendScrollDelta(dy int) {
	if dy == 0 {
		return
	}
	msg := fmt.Sprintf(`{"type":"scroll","dy":%d}`, dy)
	for _, dc := range s.tilePeersSnapshot() {
		if err := dc.SendText(msg); err != nil {
			s.removeTilePeer(dc)
		}
	}
}

func (s *h264Source) sendTile(data []byte, width, height, x, y, w, h int, ptsUs int64) error {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for row := 0; row < h; row++ {
		srcStart := ((y+row)*width + x) * 4
		dstStart := row * w * 4
		copy(img.Pix[dstStart:dstStart+w*4], data[srcStart:srcStart+w*4])
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 72}); err != nil {
		return err
	}
	payload := buf.Bytes()
	packet := make([]byte, 24+len(payload))
	binary.BigEndian.PutUint32(packet[0:4], rawFrameTileMagic)
	binary.BigEndian.PutUint32(packet[4:8], uint32(time.Now().UnixNano()/1e6))
	binary.BigEndian.PutUint32(packet[8:10], uint32(x))
	binary.BigEndian.PutUint32(packet[10:12], uint32(y))
	binary.BigEndian.PutUint32(packet[12:14], uint32(w))
	binary.BigEndian.PutUint32(packet[14:16], uint32(h))
	binary.BigEndian.PutUint32(packet[16:20], uint32(len(payload)))
	binary.BigEndian.PutUint32(packet[20:24], uint32(ptsUs&0xffffffff))
	copy(packet[24:], payload)
	for _, dc := range s.tilePeersSnapshot() {
		if err := dc.Send(packet); err != nil {
			s.removeTilePeer(dc)
		}
	}
	return nil
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (s *h264Source) pipeTo(track *webrtc.TrackLocalStaticSample, pc *webrtc.PeerConnection) {
	var lastSeq uint64
	var ptsBaseUs int64 = -1
	var lastPtsUs int64 = -1
	var forceKeySeen uint64
	var pending *h264Frame
	var pendingData []byte
	var pendingKey bool
	var pendingRelPtsUs int64
	loggedFirst := false

	// Small one-frame look-ahead: with VFR input, the duration of the current
	// sample is the delta to the next sample, not the delta from the previous one.
	// This keeps RTP pacing faithful to the real source cadence.
	flushPending := func(nextPtsUs int64, hasNext bool) error {
		if pending == nil {
			return nil
		}
		dur := time.Second / 60
		if hasNext && nextPtsUs >= pendingRelPtsUs {
			d := time.Duration(nextPtsUs-pendingRelPtsUs) * time.Microsecond
			if d >= 1*time.Millisecond && d <= 2*time.Second {
				dur = d
			}
		}
		if err := track.WriteSample(media.Sample{Data: pendingData, PacketTimestamp: uint32((pendingRelPtsUs * 90) / 1000), Duration: dur}); err != nil {
			return err
		}
		s.mu.Lock()
		s.framesSent++
		if pendingKey {
			s.keyFramesSent++
			s.configSent++
		}
		s.mu.Unlock()
		if !loggedFirst {
			s.mu.RLock()
			profile := s.profileLevelID
			s.mu.RUnlock()
			log.Printf("WebRTC first H264 sample: seq=%d pts=%d key=%t size=%d duration=%s selfContained=%t profile-level-id=%s",
				pending.seq, pending.ptsUs, pendingKey, len(pendingData), dur, containsSPSPPS(pendingData), profile)
			loggedFirst = true
		}
		pending = nil
		pendingData = nil
		pendingKey = false
		lastPtsUs = pendingRelPtsUs
		return nil
	}

	for {
		s.mu.RLock()
		ch := s.changed
		latestSeq := s.seq
		key := s.latestKey
		cfg := append([]byte(nil), s.config...)
		currentKeyRequest := s.keyRequest
		var next *h264Frame
		var overrun bool

		if lastSeq == 0 {
			next = key
		} else {
			target := lastSeq + 1
			for _, candidate := range s.history {
				if candidate.seq == target {
					next = candidate
					break
				}
			}
			if next == nil && latestSeq >= target && len(s.history) > 0 && s.history[0].seq > target {
				overrun = true
			}
		}
		s.mu.RUnlock()

		forceKey := currentKeyRequest != forceKeySeen
		if forceKey {
			forceKeySeen = currentKeyRequest
			// A browser PLI/FIR is an explicit decoder recovery request. Discard any
			// pending P-frame and restart from the newest complete IDR.
			pending = nil
			pendingData = nil
			pendingKey = false
		}

		if forceKey || overrun {
			s.mu.RLock()
			key = s.latestKey
			cfg = append([]byte(nil), s.config...)
			s.mu.RUnlock()
			if key != nil && len(cfg) > 0 {
				next = key
				s.mu.Lock()
				if overrun {
					s.frameOverruns++
				}
				s.recoveryCount++
				s.mu.Unlock()
			}
		}

		if next == nil {
			select {
			case <-ch:
			case <-time.After(50 * time.Millisecond):
			}
			if pc.ConnectionState() == webrtc.PeerConnectionStateClosed || pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
				return
			}
			continue
		}

		// First media sample must be a self-contained IDR. Never start or recover
		// a peer from a P-frame.
		if lastSeq == 0 || forceKey || overrun {
			if !((next.flags&flagKeyFrame) != 0 && len(cfg) > 0) {
				s.mu.Lock()
				s.waitedNoConfig++
				s.mu.Unlock()
				select {
				case <-ch:
				case <-time.After(50 * time.Millisecond):
				}
				continue
			}
		}

		// Configuration-only access units update the cached decoder state but are
		// never sent as standalone media samples. Advance the sequence cursor so the
		// next P-frame remains contiguous in the source stream.
		if next.flags&flagConfig != 0 && next.flags&flagKeyFrame == 0 {
			lastSeq = next.seq
			continue
		}

		data := next.data
		isKey := next.flags&flagKeyFrame != 0
		if s.isTileMode() && !isKey {
			// Tile-delta mode owns incremental updates. Keep the H264 track alive but
			// do not send predictive frames over the network. A mode transition requests
			// a fresh IDR through keyRequest, so the browser can re-base its canvas.
			lastSeq = next.seq
			continue
		}
		if isKey && len(cfg) > 0 && !containsSPSPPS(data) {
			merged := make([]byte, 0, len(cfg)+len(data))
			merged = append(merged, cfg...)
			merged = append(merged, data...)
			data = merged
		}
		if isKey && !containsSPSPPS(data) {
			s.mu.Lock()
			s.waitedNoConfig++
			s.mu.Unlock()
			select {
			case <-ch:
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}

		if ptsBaseUs < 0 {
			ptsBaseUs = next.ptsUs
		}
		relPtsUs := next.ptsUs - ptsBaseUs
		if relPtsUs < 0 || (lastPtsUs >= 0 && relPtsUs < lastPtsUs) {
			// The source session changed its PTS timeline. Restart the local timeline
			// without changing frame order.
			ptsBaseUs = next.ptsUs
			relPtsUs = 0
			lastPtsUs = -1
			pending = nil
			pendingData = nil
			pendingKey = false
		}

		// If this is not contiguous with the previous frame, do not send it. The
		// next IDR is the only safe recovery point for a predictive H.264 stream.
		if lastSeq != 0 && next.seq != lastSeq+1 && !isKey {
			continue
		}

		if pending != nil {
			if err := flushPending(relPtsUs, true); err != nil {
				log.Printf("WebRTC WriteSample failed: %v", err)
				_ = pc.Close()
				return
			}
		}

		pending = next
		pendingData = data
		pendingKey = isKey
		pendingRelPtsUs = relPtsUs
		lastSeq = next.seq

		if pc.ConnectionState() == webrtc.PeerConnectionStateClosed || pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
			return
		}
	}
}

func normalizeH264(b []byte) ([]byte, []byte, []byte, bool) {
	if len(b) == 0 {
		return nil, nil, nil, false
	}
	if hasAnnexBStartCode(b) {
		nals := splitAnnexB(b)
		var sps, pps []byte
		for _, n := range nals {
			if len(n) == 0 {
				continue
			}
			switch n[0] & 0x1f {
			case 7:
				sps = append([]byte(nil), n...)
			case 8:
				pps = append([]byte(nil), n...)
			}
		}
		return append([]byte(nil), b...), sps, pps, true
	}
	// AVCDecoderConfigurationRecord (ISO/IEC 14496-15), commonly used as MediaCodec CSD.
	if len(b) >= 7 && b[0] == 1 {
		if sps, pps, ok := parseAVCDecoderConfigurationRecord(b); ok {
			return annexBJoin(sps, pps), sps, pps, true
		}
	}
	// Length-prefixed AVCC access unit. Try 4-byte then 2-byte lengths.
	for _, n := range []int{4, 2} {
		if out, sps, pps, ok := parseLengthPrefixedNALs(b, n); ok {
			return out, sps, pps, true
		}
	}
	// Single raw NAL unit.
	if b[0]&0x1f >= 1 && b[0]&0x1f <= 23 {
		n := append([]byte(nil), b...)
		var sps, pps []byte
		if n[0]&0x1f == 7 {
			sps = n
		}
		if n[0]&0x1f == 8 {
			pps = n
		}
		return annexB(n), sps, pps, true
	}
	return nil, nil, nil, false
}

func parseAVCDecoderConfigurationRecord(b []byte) ([]byte, []byte, bool) {
	if len(b) < 7 || b[0] != 1 {
		return nil, nil, false
	}
	pos := 5
	numSPS := int(b[pos] & 0x1f)
	pos++
	var sps, pps []byte
	for i := 0; i < numSPS; i++ {
		if pos+2 > len(b) {
			return nil, nil, false
		}
		n := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		pos += 2
		if n <= 0 || pos+n > len(b) {
			return nil, nil, false
		}
		if i == 0 {
			sps = append([]byte(nil), b[pos:pos+n]...)
		}
		pos += n
	}
	if pos >= len(b) {
		return nil, nil, false
	}
	numPPS := int(b[pos])
	pos++
	for i := 0; i < numPPS; i++ {
		if pos+2 > len(b) {
			return nil, nil, false
		}
		n := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		pos += 2
		if n <= 0 || pos+n > len(b) {
			return nil, nil, false
		}
		if i == 0 {
			pps = append([]byte(nil), b[pos:pos+n]...)
		}
		pos += n
	}
	return sps, pps, len(sps) > 0 && len(pps) > 0
}

func parseLengthPrefixedNALs(b []byte, lengthBytes int) ([]byte, []byte, []byte, bool) {
	var out []byte
	var sps, pps []byte
	pos := 0
	count := 0
	for pos < len(b) {
		if pos+lengthBytes > len(b) {
			return nil, nil, nil, false
		}
		var n int
		if lengthBytes == 4 {
			n = int(binary.BigEndian.Uint32(b[pos : pos+4]))
		} else {
			n = int(binary.BigEndian.Uint16(b[pos : pos+2]))
		}
		pos += lengthBytes
		if n <= 0 || n > len(b)-pos {
			return nil, nil, nil, false
		}
		nal := b[pos : pos+n]
		pos += n
		if nal[0]&0x1f == 0 {
			return nil, nil, nil, false
		}
		out = append(out, annexB(nal)...)
		count++
		switch nal[0] & 0x1f {
		case 7:
			sps = append([]byte(nil), nal...)
		case 8:
			pps = append([]byte(nil), nal...)
		}
	}
	return out, sps, pps, count > 0
}

func splitAnnexB(b []byte) [][]byte {
	var out [][]byte
	i := 0
	for {
		start, sc := findStartCode(b, i)
		if start < 0 {
			break
		}
		nstart := start + sc
		end, _ := findStartCode(b, nstart)
		if end < 0 {
			end = len(b)
		}
		if end > nstart {
			out = append(out, b[nstart:end])
		}
		i = end
	}
	return out
}
func findStartCode(b []byte, from int) (int, int) {
	for i := from; i+3 <= len(b); i++ {
		if i+3 <= len(b) && bytes.Equal(b[i:i+3], []byte{0, 0, 1}) {
			return i, 3
		}
		if i+4 <= len(b) && bytes.Equal(b[i:i+4], []byte{0, 0, 0, 1}) {
			return i, 4
		}
	}
	return -1, 0
}
func hasAnnexBStartCode(b []byte) bool { _, n := findStartCode(b, 0); return n > 0 }
func annexB(n []byte) []byte {
	o := make([]byte, 4+len(n))
	copy(o, []byte{0, 0, 0, 1})
	copy(o[4:], n)
	return o
}
func annexBJoin(a, b []byte) []byte {
	var o []byte
	if len(a) > 0 {
		o = append(o, annexB(a)...)
	}
	if len(b) > 0 {
		o = append(o, annexB(b)...)
	}
	return o
}
func hasNALType(b []byte, want byte) bool {
	for _, n := range splitAnnexB(b) {
		if len(n) > 0 && (n[0]&0x1f) == want {
			return true
		}
	}
	return false
}

func containsSPSPPS(b []byte) bool {
	var s, p bool
	for _, n := range splitAnnexB(b) {
		if len(n) == 0 {
			continue
		}
		if n[0]&0x1f == 7 {
			s = true
		}
		if n[0]&0x1f == 8 {
			p = true
		}
	}
	return s && p
}
func profileLevelID(sps []byte) string {
	if len(sps) < 4 {
		return ""
	}
	return fmt.Sprintf("%02x%02x%02x", sps[1], sps[2], sps[3])
}
func firstBytes(b []byte, n int) string {
	if len(b) < n {
		n = len(b)
	}
	return fmt.Sprintf("% x", b[:n])
}

func getenv(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// -----------------------------------------------------------------------------
// Android/Linux internal SharedMemory transport (merged from internal_shm_linux.go)
// -----------------------------------------------------------------------------

const internalShmMagic uint32 = 0x56445342 // VDSB
const internalShmVersion uint32 = 1
const shmHeaderSize = 64
const shmSlotHeaderSize = 32

type shmMappedRing struct {
	fd         int
	data       []byte
	size       int
	typ        uint32
	slots      int
	payloadMax int
	slotStride int
}

type shmTransport struct {
	video *shmMappedRing
	audio *shmMappedRing
	raw   *shmMappedRing
}

func connectInternalShm(displayID int) (*shmTransport, error) {
	name := "@virtualdisplay-shm-" + fmt.Sprint(displayID)
	c, err := net.DialTimeout("unix", name, 2*time.Second)
	if err != nil {
		return nil, err
	}
	uc := c.(*net.UnixConn)
	defer uc.Close()
	_ = uc.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 128)
	oob := make([]byte, syscall.CmsgSpace(3*4))
	n, oobn, _, _, err := uc.ReadMsgUnix(buf, oob)
	if err != nil {
		return nil, err
	}
	if n < 68 {
		return nil, fmt.Errorf("short SHM metadata: %d", n)
	}
	if binary.BigEndian.Uint32(buf[0:4]) != internalShmMagic || binary.BigEndian.Uint32(buf[4:8]) != internalShmVersion {
		return nil, fmt.Errorf("bad SHM metadata header")
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return nil, err
	}
	var fds []int
	for _, m := range msgs {
		fs, err := syscall.ParseUnixRights(&m)
		if err != nil {
			return nil, err
		}
		fds = append(fds, fs...)
	}
	if len(fds) < 3 {
		return nil, fmt.Errorf("SHM bridge sent %d fds, want 3", len(fds))
	}
	off := 20
	rings := make([]*shmMappedRing, 0, 3)
	for i := 0; i < 3; i++ {
		typ := binary.BigEndian.Uint32(buf[off : off+4])
		size := int(binary.BigEndian.Uint32(buf[off+4 : off+8]))
		slots := int(binary.BigEndian.Uint32(buf[off+8 : off+12]))
		payload := int(binary.BigEndian.Uint32(buf[off+12 : off+16]))
		off += 16
		r, err := mapShmRing(fds[i], size, typ, slots, payload)
		if err != nil {
			for _, fd := range fds {
				_ = syscall.Close(fd)
			}
			return nil, err
		}
		rings = append(rings, r)
	}
	return &shmTransport{video: rings[0], audio: rings[1], raw: rings[2]}, nil
}

func (s *shmTransport) close() {
	if s == nil {
		return
	}
	if s.video != nil {
		s.video.close()
		s.video = nil
	}
	if s.audio != nil {
		s.audio.close()
		s.audio = nil
	}
	if s.raw != nil {
		s.raw.close()
		s.raw = nil
	}
}

func mapShmRing(fd, size int, typ uint32, slots, payloadMax int) (*shmMappedRing, error) {
	if size < shmHeaderSize || slots <= 0 || payloadMax <= 0 {
		return nil, fmt.Errorf("invalid SHM ring size=%d slots=%d payload=%d", size, slots, payloadMax)
	}
	data, err := syscall.Mmap(fd, 0, size, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		return nil, err
	}
	if binary.BigEndian.Uint32(data[0:4]) != 0x56445348 || binary.BigEndian.Uint32(data[4:8]) != 1 {
		_ = syscall.Munmap(data)
		return nil, fmt.Errorf("bad ring header")
	}
	return &shmMappedRing{fd: fd, data: data, size: size, typ: typ, slots: slots, payloadMax: payloadMax, slotStride: shmSlotHeaderSize + payloadMax}, nil
}

func (r *shmMappedRing) close() {
	if r == nil {
		return
	}
	if r.data != nil {
		_ = syscall.Munmap(r.data)
		r.data = nil
	}
	if r.fd >= 0 {
		_ = syscall.Close(r.fd)
		r.fd = -1
	}
}

func (r *shmMappedRing) latestSeq() uint64 { return binary.BigEndian.Uint64(r.data[32:40]) }

func (r *shmMappedRing) readSeq(want uint64) (pts int64, flags uint32, w, h int, payload []byte, ok bool, overrun bool) {
	latest := r.latestSeq()
	if latest < want {
		return 0, 0, 0, 0, nil, false, false
	}
	if latest-want >= uint64(r.slots) {
		return 0, 0, 0, 0, nil, false, true
	}
	slot := int((want - 1) % uint64(r.slots))
	base := shmHeaderSize + slot*r.slotStride
	seq1 := binary.BigEndian.Uint64(r.data[base : base+8])
	if seq1 != want {
		return 0, 0, 0, 0, nil, false, seq1 > want
	}
	flags = binary.BigEndian.Uint32(r.data[base+16 : base+20])
	n := int(binary.BigEndian.Uint32(r.data[base+20 : base+24]))
	w = int(binary.BigEndian.Uint32(r.data[base+24 : base+28]))
	h = int(binary.BigEndian.Uint32(r.data[base+28 : base+32]))
	if n < 0 || n > r.payloadMax {
		return 0, 0, 0, 0, nil, false, false
	}
	pts = int64(binary.BigEndian.Uint64(r.data[base+8 : base+16]))
	p := make([]byte, n)
	copy(p, r.data[base+32:base+32+n])
	seq2 := binary.BigEndian.Uint64(r.data[base : base+8])
	if seq2 != seq1 {
		return 0, 0, 0, 0, nil, false, false
	}
	return pts, flags, w, h, p, true, false
}
