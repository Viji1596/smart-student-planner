const { verifyAccessToken } = require("../lib/tokens");

function requireAuth(req, res, next) {
  const header = req.headers.authorization || "";
  const [scheme, token] = header.split(" ");

  if (scheme !== "Bearer" || !token) {
    return res.status(401).json({ message: "Authentication required" });
  }

  try {
    req.user = verifyAccessToken(token);
    return next();
  } catch {
    // The app reacts to 401 by calling /auth/refresh
    return res.status(401).json({ message: "Invalid or expired token" });
  }
}

module.exports = requireAuth;
