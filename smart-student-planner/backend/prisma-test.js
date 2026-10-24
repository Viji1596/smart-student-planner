const prisma = require("./lib/prisma");

prisma.$queryRawUnsafe("SELECT 1")
  .then((result) => {
    console.log("PRISMA OK:", result);
  })
  .catch((error) => {
    console.error("PRISMA ERROR:", error.message);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });