package com.prism.launcher

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.prism.launcher.agentic.AgenticDao
import com.prism.launcher.agentic.AgenticDao_Impl
import com.prism.launcher.messaging.AiMessageDao
import com.prism.launcher.messaging.AiMessageDao_Impl
import com.prism.launcher.social.SocialDao
import com.prism.launcher.social.SocialDao_Impl
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _installedAppDao: Lazy<InstalledAppDao> = lazy {
    InstalledAppDao_Impl(this)
  }

  private val _appLaunchStatDao: Lazy<AppLaunchStatDao> = lazy {
    AppLaunchStatDao_Impl(this)
  }

  private val _aiMessageDao: Lazy<AiMessageDao> = lazy {
    AiMessageDao_Impl(this)
  }

  private val _socialDao: Lazy<SocialDao> = lazy {
    SocialDao_Impl(this)
  }

  private val _agenticDao: Lazy<AgenticDao> = lazy {
    AgenticDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(11,
        "f2ce9da3afdc28c18ca927b22d88b6a7", "85ce1a848ac6c40b1686b7166a14541c") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `installed_apps` (`packageName` TEXT NOT NULL, `activityClass` TEXT NOT NULL, PRIMARY KEY(`packageName`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `app_launch_stats` (`componentName` TEXT NOT NULL, `hourOfDay` INTEGER NOT NULL, `launchCount` INTEGER NOT NULL, PRIMARY KEY(`componentName`, `hourOfDay`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `ai_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `isSent` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `attachmentUri` TEXT, `attachmentType` TEXT)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `postId` TEXT NOT NULL, `authorId` TEXT NOT NULL, `authorName` TEXT NOT NULL, `authorHandle` TEXT NOT NULL, `authorAvatarUrl` TEXT, `content` TEXT NOT NULL, `imageUrl` TEXT, `timestamp` INTEGER NOT NULL, `likesCount` INTEGER NOT NULL, `repostCount` INTEGER NOT NULL, `isUserPost` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_bots` (`botId` TEXT NOT NULL, `name` TEXT NOT NULL, `handle` TEXT NOT NULL, `bio` TEXT NOT NULL, `avatarUrl` TEXT, `personaType` TEXT NOT NULL, `personality` TEXT NOT NULL, `lastPostTime` INTEGER NOT NULL, PRIMARY KEY(`botId`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_comments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `commentId` TEXT NOT NULL, `postId` TEXT NOT NULL, `parentCommentId` TEXT, `authorId` TEXT NOT NULL, `authorName` TEXT NOT NULL, `authorHandle` TEXT NOT NULL, `authorAvatarUrl` TEXT, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_follows` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `botId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `social_interactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `postId` TEXT NOT NULL, `actorId` TEXT NOT NULL, `actorName` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `agentic_tools` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `parametersJson` TEXT NOT NULL, `httpMethod` TEXT NOT NULL, `httpUrl` TEXT NOT NULL, `httpHeadersJson` TEXT NOT NULL, `httpBodyTemplate` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `agentic_syntaxes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `systemPromptTemplate` TEXT NOT NULL, `toolFormatTemplate` TEXT NOT NULL, `callExtractionRegex` TEXT NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f2ce9da3afdc28c18ca927b22d88b6a7')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `installed_apps`")
        connection.execSQL("DROP TABLE IF EXISTS `app_launch_stats`")
        connection.execSQL("DROP TABLE IF EXISTS `ai_messages`")
        connection.execSQL("DROP TABLE IF EXISTS `social_posts`")
        connection.execSQL("DROP TABLE IF EXISTS `social_bots`")
        connection.execSQL("DROP TABLE IF EXISTS `social_messages`")
        connection.execSQL("DROP TABLE IF EXISTS `social_comments`")
        connection.execSQL("DROP TABLE IF EXISTS `social_follows`")
        connection.execSQL("DROP TABLE IF EXISTS `social_interactions`")
        connection.execSQL("DROP TABLE IF EXISTS `agentic_tools`")
        connection.execSQL("DROP TABLE IF EXISTS `agentic_syntaxes`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsInstalledApps: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsInstalledApps.put("packageName", TableInfo.Column("packageName", "TEXT", true, 1,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsInstalledApps.put("activityClass", TableInfo.Column("activityClass", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysInstalledApps: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesInstalledApps: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoInstalledApps: TableInfo = TableInfo("installed_apps", _columnsInstalledApps,
            _foreignKeysInstalledApps, _indicesInstalledApps)
        val _existingInstalledApps: TableInfo = read(connection, "installed_apps")
        if (!_infoInstalledApps.equals(_existingInstalledApps)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |installed_apps(com.prism.launcher.InstalledAppEntity).
              | Expected:
              |""".trimMargin() + _infoInstalledApps + """
              |
              | Found:
              |""".trimMargin() + _existingInstalledApps)
        }
        val _columnsAppLaunchStats: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAppLaunchStats.put("componentName", TableInfo.Column("componentName", "TEXT", true,
            1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppLaunchStats.put("hourOfDay", TableInfo.Column("hourOfDay", "INTEGER", true, 2,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAppLaunchStats.put("launchCount", TableInfo.Column("launchCount", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAppLaunchStats: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAppLaunchStats: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoAppLaunchStats: TableInfo = TableInfo("app_launch_stats", _columnsAppLaunchStats,
            _foreignKeysAppLaunchStats, _indicesAppLaunchStats)
        val _existingAppLaunchStats: TableInfo = read(connection, "app_launch_stats")
        if (!_infoAppLaunchStats.equals(_existingAppLaunchStats)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |app_launch_stats(com.prism.launcher.AppLaunchStatEntity).
              | Expected:
              |""".trimMargin() + _infoAppLaunchStats + """
              |
              | Found:
              |""".trimMargin() + _existingAppLaunchStats)
        }
        val _columnsAiMessages: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAiMessages.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAiMessages.put("text", TableInfo.Column("text", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAiMessages.put("isSent", TableInfo.Column("isSent", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAiMessages.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAiMessages.put("attachmentUri", TableInfo.Column("attachmentUri", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAiMessages.put("attachmentType", TableInfo.Column("attachmentType", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAiMessages: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAiMessages: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoAiMessages: TableInfo = TableInfo("ai_messages", _columnsAiMessages,
            _foreignKeysAiMessages, _indicesAiMessages)
        val _existingAiMessages: TableInfo = read(connection, "ai_messages")
        if (!_infoAiMessages.equals(_existingAiMessages)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |ai_messages(com.prism.launcher.messaging.AiMessageEntity).
              | Expected:
              |""".trimMargin() + _infoAiMessages + """
              |
              | Found:
              |""".trimMargin() + _existingAiMessages)
        }
        val _columnsSocialPosts: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialPosts.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("postId", TableInfo.Column("postId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("authorId", TableInfo.Column("authorId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("authorName", TableInfo.Column("authorName", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("authorHandle", TableInfo.Column("authorHandle", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("authorAvatarUrl", TableInfo.Column("authorAvatarUrl", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("content", TableInfo.Column("content", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("imageUrl", TableInfo.Column("imageUrl", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("likesCount", TableInfo.Column("likesCount", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("repostCount", TableInfo.Column("repostCount", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialPosts.put("isUserPost", TableInfo.Column("isUserPost", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialPosts: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialPosts: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialPosts: TableInfo = TableInfo("social_posts", _columnsSocialPosts,
            _foreignKeysSocialPosts, _indicesSocialPosts)
        val _existingSocialPosts: TableInfo = read(connection, "social_posts")
        if (!_infoSocialPosts.equals(_existingSocialPosts)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_posts(com.prism.launcher.social.SocialPostEntity).
              | Expected:
              |""".trimMargin() + _infoSocialPosts + """
              |
              | Found:
              |""".trimMargin() + _existingSocialPosts)
        }
        val _columnsSocialBots: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialBots.put("botId", TableInfo.Column("botId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("handle", TableInfo.Column("handle", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("bio", TableInfo.Column("bio", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("avatarUrl", TableInfo.Column("avatarUrl", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("personaType", TableInfo.Column("personaType", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("personality", TableInfo.Column("personality", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialBots.put("lastPostTime", TableInfo.Column("lastPostTime", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialBots: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialBots: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialBots: TableInfo = TableInfo("social_bots", _columnsSocialBots,
            _foreignKeysSocialBots, _indicesSocialBots)
        val _existingSocialBots: TableInfo = read(connection, "social_bots")
        if (!_infoSocialBots.equals(_existingSocialBots)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_bots(com.prism.launcher.social.SocialBotEntity).
              | Expected:
              |""".trimMargin() + _infoSocialBots + """
              |
              | Found:
              |""".trimMargin() + _existingSocialBots)
        }
        val _columnsSocialMessages: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialMessages.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialMessages.put("chatId", TableInfo.Column("chatId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialMessages.put("senderId", TableInfo.Column("senderId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialMessages.put("content", TableInfo.Column("content", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialMessages.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialMessages: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialMessages: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialMessages: TableInfo = TableInfo("social_messages", _columnsSocialMessages,
            _foreignKeysSocialMessages, _indicesSocialMessages)
        val _existingSocialMessages: TableInfo = read(connection, "social_messages")
        if (!_infoSocialMessages.equals(_existingSocialMessages)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_messages(com.prism.launcher.social.SocialMessageEntity).
              | Expected:
              |""".trimMargin() + _infoSocialMessages + """
              |
              | Found:
              |""".trimMargin() + _existingSocialMessages)
        }
        val _columnsSocialComments: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialComments.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("commentId", TableInfo.Column("commentId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("postId", TableInfo.Column("postId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("parentCommentId", TableInfo.Column("parentCommentId", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("authorId", TableInfo.Column("authorId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("authorName", TableInfo.Column("authorName", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("authorHandle", TableInfo.Column("authorHandle", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("authorAvatarUrl", TableInfo.Column("authorAvatarUrl", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("content", TableInfo.Column("content", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialComments.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialComments: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialComments: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialComments: TableInfo = TableInfo("social_comments", _columnsSocialComments,
            _foreignKeysSocialComments, _indicesSocialComments)
        val _existingSocialComments: TableInfo = read(connection, "social_comments")
        if (!_infoSocialComments.equals(_existingSocialComments)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_comments(com.prism.launcher.social.SocialCommentEntity).
              | Expected:
              |""".trimMargin() + _infoSocialComments + """
              |
              | Found:
              |""".trimMargin() + _existingSocialComments)
        }
        val _columnsSocialFollows: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialFollows.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialFollows.put("botId", TableInfo.Column("botId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialFollows.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialFollows: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialFollows: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialFollows: TableInfo = TableInfo("social_follows", _columnsSocialFollows,
            _foreignKeysSocialFollows, _indicesSocialFollows)
        val _existingSocialFollows: TableInfo = read(connection, "social_follows")
        if (!_infoSocialFollows.equals(_existingSocialFollows)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_follows(com.prism.launcher.social.SocialFollowEntity).
              | Expected:
              |""".trimMargin() + _infoSocialFollows + """
              |
              | Found:
              |""".trimMargin() + _existingSocialFollows)
        }
        val _columnsSocialInteractions: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSocialInteractions.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialInteractions.put("postId", TableInfo.Column("postId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialInteractions.put("actorId", TableInfo.Column("actorId", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialInteractions.put("actorName", TableInfo.Column("actorName", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialInteractions.put("type", TableInfo.Column("type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSocialInteractions.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSocialInteractions: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSocialInteractions: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoSocialInteractions: TableInfo = TableInfo("social_interactions",
            _columnsSocialInteractions, _foreignKeysSocialInteractions, _indicesSocialInteractions)
        val _existingSocialInteractions: TableInfo = read(connection, "social_interactions")
        if (!_infoSocialInteractions.equals(_existingSocialInteractions)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |social_interactions(com.prism.launcher.social.SocialInteractionEntity).
              | Expected:
              |""".trimMargin() + _infoSocialInteractions + """
              |
              | Found:
              |""".trimMargin() + _existingSocialInteractions)
        }
        val _columnsAgenticTools: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAgenticTools.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("description", TableInfo.Column("description", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("parametersJson", TableInfo.Column("parametersJson", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("httpMethod", TableInfo.Column("httpMethod", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("httpUrl", TableInfo.Column("httpUrl", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("httpHeadersJson", TableInfo.Column("httpHeadersJson", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("httpBodyTemplate", TableInfo.Column("httpBodyTemplate", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticTools.put("enabled", TableInfo.Column("enabled", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAgenticTools: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAgenticTools: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoAgenticTools: TableInfo = TableInfo("agentic_tools", _columnsAgenticTools,
            _foreignKeysAgenticTools, _indicesAgenticTools)
        val _existingAgenticTools: TableInfo = read(connection, "agentic_tools")
        if (!_infoAgenticTools.equals(_existingAgenticTools)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |agentic_tools(com.prism.launcher.agentic.AgenticToolEntity).
              | Expected:
              |""".trimMargin() + _infoAgenticTools + """
              |
              | Found:
              |""".trimMargin() + _existingAgenticTools)
        }
        val _columnsAgenticSyntaxes: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAgenticSyntaxes.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticSyntaxes.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticSyntaxes.put("systemPromptTemplate", TableInfo.Column("systemPromptTemplate",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticSyntaxes.put("toolFormatTemplate", TableInfo.Column("toolFormatTemplate",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAgenticSyntaxes.put("callExtractionRegex", TableInfo.Column("callExtractionRegex",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAgenticSyntaxes: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAgenticSyntaxes: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoAgenticSyntaxes: TableInfo = TableInfo("agentic_syntaxes", _columnsAgenticSyntaxes,
            _foreignKeysAgenticSyntaxes, _indicesAgenticSyntaxes)
        val _existingAgenticSyntaxes: TableInfo = read(connection, "agentic_syntaxes")
        if (!_infoAgenticSyntaxes.equals(_existingAgenticSyntaxes)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |agentic_syntaxes(com.prism.launcher.agentic.AgenticSyntaxEntity).
              | Expected:
              |""".trimMargin() + _infoAgenticSyntaxes + """
              |
              | Found:
              |""".trimMargin() + _existingAgenticSyntaxes)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "installed_apps",
        "app_launch_stats", "ai_messages", "social_posts", "social_bots", "social_messages",
        "social_comments", "social_follows", "social_interactions", "agentic_tools",
        "agentic_syntaxes")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(InstalledAppDao::class, InstalledAppDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AppLaunchStatDao::class, AppLaunchStatDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AiMessageDao::class, AiMessageDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(SocialDao::class, SocialDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AgenticDao::class, AgenticDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun installedAppDao(): InstalledAppDao = _installedAppDao.value

  public override fun appLaunchStatDao(): AppLaunchStatDao = _appLaunchStatDao.value

  public override fun aiMessageDao(): AiMessageDao = _aiMessageDao.value

  public override fun socialDao(): SocialDao = _socialDao.value

  public override fun agenticDao(): AgenticDao = _agenticDao.value
}
