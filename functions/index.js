// "Wake-up bell" for SecureCall Family. Receives { room } from the parent phone and rings the FCM topic
// named after that mailbox id. The push carries no call data and no secret; the real call request is
// still the encrypted message in the mailbox. The room id is a hash of the pairing code, so it can't be guessed.
const { onRequest } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const last = new Map();

exports.wake = onRequest({ maxInstances: 5, cors: false }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).send("POST only");
  const room = String((req.body && req.body.room) || "");
  if (!/^sc-[0-9a-f]{40}$/.test(room)) return res.status(400).send("bad room");
  const now = Date.now();
  if (now - (last.get(room) || 0) < 8000) return res.status(429).send("slow down");
  last.set(room, now);
  if (last.size > 5000) last.clear();
  try {
    await getMessaging().send({
      topic: room,
      data: { w: "1", t: String(now) },
      android: { priority: "high", ttl: 60000 },
    });
    return res.status(200).send("ok");
  } catch (e) {
    console.error(e);
    return res.status(500).send("fail");
  }
});
