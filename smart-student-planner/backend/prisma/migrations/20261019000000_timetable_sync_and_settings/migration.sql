-- AlterTable: timetable entries become syncable
ALTER TABLE "TimetableEntry" ADD COLUMN "deletedAt" TIMESTAMP(3),
ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "TimetableEntry" ALTER COLUMN "updatedAt" DROP DEFAULT;

-- CreateTable
CREATE TABLE "UserSettings" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "wakeTime" TEXT NOT NULL DEFAULT '06:30',
    "sleepTime" TEXT NOT NULL DEFAULT '23:00',
    "morningRoutineMinutes" INTEGER NOT NULL DEFAULT 45,
    "breakfastStart" TEXT NOT NULL DEFAULT '08:00',
    "breakfastMinutes" INTEGER NOT NULL DEFAULT 20,
    "lunchStart" TEXT NOT NULL DEFAULT '13:00',
    "lunchMinutes" INTEGER NOT NULL DEFAULT 40,
    "dinnerStart" TEXT NOT NULL DEFAULT '20:00',
    "dinnerMinutes" INTEGER NOT NULL DEFAULT 40,
    "timezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "UserSettings_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "UserSettings_userId_key" ON "UserSettings"("userId");

-- CreateIndex
CREATE INDEX "TimetableEntry_userId_updatedAt_idx" ON "TimetableEntry"("userId", "updatedAt");

-- AddForeignKey
ALTER TABLE "UserSettings" ADD CONSTRAINT "UserSettings_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
