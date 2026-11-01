const functions = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
admin.initializeApp();

/**
 * POST { roomId: "<the code-derived hash>" }
 * Looks up that room's child token in Firestore (written by TokenRegistrar.kt on the child
 * phone) and sends a bare data-only "ring" push — no call content, nothing identifying. The
 * actual encrypted call request still travels separately through the existing ntfy.sh mailbox;
 * this function's only job is to help Android wake the child app in time to read it.
 *
 * Deliberately minimal: no auth check beyond holding a valid roomId, because a roomId is already
 * an unguessable hash of the family's own secret pairing code (nobody outside the two paired
 * phones can derive it) — same trust model as the mailbox itself. If you want to harden this
 * further (e.g. rate limiting per roomId), see the note at the bottom of functions/README.md.
 */
exports.sendWake = functions.onRequest({ cors: true }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).send("POST only");
  const roomId = (req.body && req.body.roomId || "").toString();
  if (!/^sc-[0-9a-f]{40}$/.test(roomId)) return res.status(400).send("bad roomId");

  const doc = await admin.firestore().collection("fcm_tokens").doc(roomId).get();
  if (!doc.exists) return res.status(404).send("no token registered for this room yet");
  const token = doc.data().token;
  if (!token) return res.status(404).send("no token");

  try {
    await admin.messaging().send({
      token,
      data: { type: "ring" },
      android: { priority: "high" },
    });
    res.status(200).send("ok");
  } catch (e) {
    // A stale/uninstalled token is expected over time; clear it so we stop trying.
    if (e.code === "messaging/registration-token-not-registered") {
      await admin.firestore().collection("fcm_tokens").doc(roomId).delete();
    }
    res.status(500).send(String(e));
  }
});
