const express = require("express");
const { OAuth2Client } = require("google-auth-library");
const { z } = require("zod");
const prisma = require("../lib/prisma");
const requireAuth = require("../middleware/authMiddleware");
const {
  signAccessToken,
  newRefreshToken,
  hashToken,
} = require("../lib/tokens");

const router = express.Router();
const googleClient = new OAuth2Client();

const googleBody = z.object({ idToken: z.string().min(20) });
const refreshBody = z.object({ refreshToken: z.string().min(20) });

async function issueTokens(user) {
  const refresh = newRefreshToken();
  await prisma.refreshToken.create({
    data: {
      userId: user.id,
      tokenHash: refresh.tokenHash,
      expiresAt: refresh.expiresAt,
    },
  });
  return {
    accessToken: signAccessToken(user.id),
    refreshToken: refresh.token,
    user: { id: user.id, email: user.email, name: user.name },
  };
}

// The Android app signs in with Google, then sends the Google ID token here.
router.post("/google", async (req, res) => {
  const parsed = googleBody.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ message: "idToken is required" });
  }

  try {
    const ticket = await googleClient.verifyIdToken({
      idToken: parsed.data.idToken,
      audience: process.env.GOOGLE_WEB_CLIENT_ID,
    });
    const profile = ticket.getPayload();

    if (!profile?.sub || !profile.email || !profile.email_verified) {
      return res.status(401).json({ message: "Google account not verified" });
    }

    const user = await prisma.user.upsert({
      where: { googleId: profile.sub },
      update: { email: profile.email, name: profile.name || profile.email },
      create: {
        googleId: profile.sub,
        email: profile.email,
        name: profile.name || profile.email,
      },
    });

    res.json(await issueTokens(user));
  } catch (error) {
    console.error("Google sign-in failed:", error.message);
    res.status(401).json({ message: "Google sign-in failed" });
  }
});

// Rotating refresh: the old token is revoked, a new pair is returned.
router.post("/refresh", async (req, res) => {
  const parsed = refreshBody.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ message: "refreshToken is required" });
  }

  try {
    const stored = await prisma.refreshToken.findUnique({
      where: { tokenHash: hashToken(parsed.data.refreshToken) },
      include: { user: true },
    });

    if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
      return res.status(401).json({ message: "Invalid refresh token" });
    }

    await prisma.refreshToken.update({
      where: { id: stored.id },
      data: { revokedAt: new Date() },
    });

    res.json(await issueTokens(stored.user));
  } catch (error) {
    console.error("Refresh failed:", error.message);
    res.status(500).json({ message: "Failed to refresh session" });
  }
});

router.post("/logout", async (req, res) => {
  const parsed = refreshBody.safeParse(req.body);
  if (parsed.success) {
    await prisma.refreshToken.updateMany({
      where: { tokenHash: hashToken(parsed.data.refreshToken), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }
  res.json({ message: "Logged out" });
});

router.get("/me", requireAuth, async (req, res) => {
  const user = await prisma.user.findUnique({ where: { id: req.user.id } });
  if (!user) return res.status(401).json({ message: "User not found" });
  res.json({ id: user.id, email: user.email, name: user.name });
});

module.exports = router;
