const crypto = require("crypto");
const jwt = require("jsonwebtoken");

const ACCESS_TTL = "30m";
const REFRESH_TTL_DAYS = 60;

function getSecret() {
  if (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 16) {
    throw new Error("JWT_SECRET must be set (at least 16 characters)");
  }
  return process.env.JWT_SECRET;
}

function signAccessToken(userId) {
  return jwt.sign({ sub: userId }, getSecret(), { expiresIn: ACCESS_TTL });
}

function verifyAccessToken(token) {
  const payload = jwt.verify(token, getSecret());
  return { id: payload.sub };
}

// Refresh tokens are random strings; only their SHA-256 hash is stored.
function newRefreshToken() {
  const token = crypto.randomBytes(48).toString("hex");
  return {
    token,
    tokenHash: hashToken(token),
    expiresAt: new Date(Date.now() + REFRESH_TTL_DAYS * 24 * 60 * 60 * 1000),
  };
}

function hashToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

module.exports = {
  signAccessToken,
  verifyAccessToken,
  newRefreshToken,
  hashToken,
};
