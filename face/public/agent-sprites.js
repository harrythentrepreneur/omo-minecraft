// No-op stub for the omo-mc face. Real omo opens a WebSocket to /omo/ws
// and surfaces a "helper sprite" each time a tool fires, but omo-mc has no
// such bus — face/server.js explicitly 404s /omo/ws. Returning null is
// fine; avatar.js's createHologramScene guards every spriteLayer call with
// `if (spriteLayer) …`, so the rest of the scene runs normally.
//
// IMPORTANT: do NOT open a WebSocket here. The previous full-omo version
// of this file opened ws://host/omo/ws with exponential backoff, which
// resulted in ~3 reconnect attempts/second hitting the face server and
// noisy [sprites] ws error spam in the client console.
export function installSpriteLayer(_scene, _avatar, _opts) {
  return null;
}
