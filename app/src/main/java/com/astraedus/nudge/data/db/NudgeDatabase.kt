package com.astraedus.nudge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.astraedus.nudge.data.db.dao.AppGroupDao
import com.astraedus.nudge.data.db.dao.BlockRuleDao
import com.astraedus.nudge.data.db.dao.UsageEventDao
import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.db.entity.UsageEvent

@Database(
    entities = [
        BlockRule::class,
        AppGroup::class,
        AppGroupMember::class,
        UsageEvent::class
    ],
    version = 9,
    exportSchema = false
)
abstract class NudgeDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun appGroupDao(): AppGroupDao
    abstract fun usageEventDao(): UsageEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Schedule-based rules
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleDays TEXT")
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleStartMinute INTEGER")
                db.execSQL("ALTER TABLE block_rules ADD COLUMN scheduleEndMinute INTEGER")
                // In-app feature blocking
                db.execSQL("ALTER TABLE block_rules ADD COLUMN inAppFeatures TEXT")
                // Grayscale mode
                db.execSQL("ALTER TABLE block_rules ADD COLUMN grayscale INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_events ADD COLUMN userChangedMind INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN showCounter INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN autoKickAfter INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN showTimeRemaining INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE block_rules ADD COLUMN autoKickCooldownSeconds INTEGER NOT NULL DEFAULT 60")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN webDomains TEXT DEFAULT NULL")
            }
        }

        /** Time-based auto-kick threshold, in minutes of session foreground time (NULL = off). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN autoKickAfterMinutes INTEGER DEFAULT NULL")
            }
        }

        /**
         * Independent block mode for a rule's web domains (issue #21). NULL = inherit the
         * app-level `mode`, so every existing rule keeps its exact current behaviour.
         *
         * The one deliberate behaviour change is the second statement. Rules with `mode = 'NONE'`
         * AND configured `webDomains` are the bug: web enforcement ran through the app-level mode,
         * so those domains silently blocked NOTHING — the worst failure class for a blocker. Such
         * rows exist in the wild (the editor kept previously-entered domains when whole-app
         * blocking was switched off), and they were only ever reachable by a user who had opted
         * into web blocking. They are repaired to DELAY rather than HARD_BLOCK: the rule already
         * carries a `delaySeconds`, and DELAY matches the fallback the editor itself uses when
         * there is no prior blocking choice to restore. The user can change it in the editor,
         * which now shows the website mode explicitly.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE block_rules ADD COLUMN webBlockMode TEXT DEFAULT NULL")
                db.execSQL(
                    "UPDATE block_rules SET webBlockMode = 'DELAY' " +
                        "WHERE mode = 'NONE' AND webDomains IS NOT NULL AND webDomains != ''"
                )
            }
        }
    }
}
