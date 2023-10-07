package com.example.cpudefense

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Window
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainGameActivity : Activity() {
    private var mainDelay: Long = 0
    private val effectsDelay: Long = 15
    lateinit var theGame: Game
    lateinit var theGameView: GameView
    private var startOnLevel: Stage.Identifier? = null
    private var resumeGame = true
    private var gameIsRunning = true  // flag used to keep the threads running. Set to false when leaving activity


    /* properties used for assuring a constant frame rate */
    /** the desired frame delay */
    val defaultMainDelay = 50L
    var timeAtStartOfCycle     = SystemClock.uptimeMillis()
    var timeAfterCycle         = SystemClock.uptimeMillis()
    var lastTimeAtStartOfCycle = SystemClock.uptimeMillis()
    /** time needed for update and display within one cycle */
    var elapsed: Long = 0
    /* additional properties for displaying an average frame rate */
    /** how many samples in one count */
    val meanCount = 10
    /** how many samples have been taken */
    var frameCount = 0
    /** cumulated time */
    var frameTimeSum = 0L

    enum class GameActivityStatus { PLAYING, BETWEEN_LEVELS }

    data class Settings(
        var configDisableBackground: Boolean = true,
        var configShowAttsInRange: Boolean = false,
        var configUseLargeButtons: Boolean = false,
        var showFramerate: Boolean = false
    )
    var settings = Settings()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        /* here, the size of the surfaces might not be known */
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main_game)
        theGame = Game(this)
        theGameView = GameView(this, theGame)

        val parentView: FrameLayout? = findViewById(R.id.gameFrameLayout)
        parentView?.addView(theGameView)

        if (intent.getBooleanExtra("RESET_PROGRESS", false) == false)
        {
            startOnLevel = Stage.Identifier(
                series = intent.getIntExtra("START_ON_SERIES", 1),
                number = intent.getIntExtra("START_ON_STAGE", 1)
            )
        }
        else
            startOnLevel = null
        if (!intent.getBooleanExtra("RESUME_GAME", false))
            resumeGame = false
        theGameView.setup()
    }

    override fun onPause() {
        // this method get executed when the user presses the system's "back" button,
        // but also when she navigates to another app
        saveState()
        gameIsRunning = false
        super.onPause()
    }

    override fun onResume()
            /** this function gets called in any case, regardless of whether
             * a new game is started or the user just navigates back to the app.
             * theGame already exists when we come here.
             */
    {
        super.onResume()
        loadSettings()
        when
        {
            resumeGame -> resumeCurrentGame()
            startOnLevel == null -> startNewGame()
            else -> startGameAtLevel(startOnLevel ?: Stage.Identifier())
        }
        resumeGame = true
        gameIsRunning = true
        startGameThreads()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startNewGame()
    /** starts a new game from level 1, discarding all progress */
    {
        theGame.setLastPlayedStage(Stage.Identifier())
        theGame.beginGame(resetProgress = true)
    }

    private fun startGameAtLevel(level: Stage.Identifier)
    /** continues a current match at a given level, keeping the progress and upgrades */
    {
        theGame.state.startingLevel = level
        theGame.beginGame(resetProgress = false)
    }

    private fun resumeCurrentGame()
    /** continues at exactly the same point within a level, restoring the complete game state.
      */
    {
        loadState()
        theGame.resumeGame()
        if (theGame.state.phase == Game.GamePhase.RUNNING) {
            runOnUiThread {
                val toast: Toast = Toast.makeText(this, "Stage %d".format(theGame.currentStage?.getLevel()), Toast.LENGTH_SHORT )
                toast.show()
            }
        }
    }

    private fun startGameThreads()
    {

        GlobalScope.launch{ delay(mainDelay); update(); }

        GlobalScope.launch{ delay(effectsDelay); updateGraphicalEffects(); }
    }

    fun loadSettings()
    /** load global configuration and debug settings from preferences */
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        settings.configDisableBackground = prefs.getBoolean("DISABLE_BACKGROUND", false)
        settings.configShowAttsInRange = prefs.getBoolean("SHOW_ATTS_IN_RANGE", false)
        settings.configUseLargeButtons = prefs.getBoolean("USE_LARGE_BUTTONS", false)
        settings.showFramerate = prefs.getBoolean("SHOW_FRAMERATE", false)
    }

    fun setGameSpeed(speed: Game.GameSpeed)
    {
        theGame.global.speed = speed
        if (speed == Game.GameSpeed.MAX) {
            mainDelay = 0
            theGame.background?.frozen = true
        }
        else {
            mainDelay = defaultMainDelay
            theGame.background?.frozen = false
        }
    }

    fun showReturnDialog()
    {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(resources.getString(R.string.query_quit))
            .setCancelable(true)
            .setPositiveButton(resources.getString(R.string.replay)) { dialog, id -> replayLevel() }
            .setNegativeButton(resources.getString(R.string.return_to_main_menu)) { dialog, id -> returnToMainMenu() }
        val alert = builder.create()
        alert.show()
    }
    
    fun returnToMainMenu()
    {
        saveState()
        finish()
    }
    
    fun replayLevel()
    {
        theGame.currentStage?.let { startGameAtLevel(it.data.ident) }
    }

    private fun update()
    {
        if (gameIsRunning) {
            lastTimeAtStartOfCycle = timeAtStartOfCycle
            timeAtStartOfCycle = SystemClock.uptimeMillis()

            theGame.update()
            theGameView.display()

            timeAfterCycle = SystemClock.uptimeMillis()
            elapsed =  timeAfterCycle - timeAtStartOfCycle
            val wait: Long = if (mainDelay>elapsed) mainDelay-elapsed-1 else 0  // rest of time in this cycle
            frameTimeSum += timeAtStartOfCycle-lastTimeAtStartOfCycle
            frameCount += 1
            if (frameCount>=meanCount )
            {
                theGame.framerate = (frameTimeSum / frameCount).toDouble()
                frameCount = 0
                frameTimeSum = 0
            }

            GlobalScope.launch { delay(wait); update() }
        }
    }

    private fun updateGraphicalEffects()
    {
        if (gameIsRunning) {
            theGame.updateEffects()
            theGameView.theEffects?.updateGraphicalEffects()
            GlobalScope.launch { delay(effectsDelay); updateGraphicalEffects() }
        }
    }

    fun setGameActivityStatus(status: GameActivityStatus)
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        val editor = prefs.edit()
        when (status)
        {
            GameActivityStatus.PLAYING -> editor.putString("STATUS", "running")
            GameActivityStatus.BETWEEN_LEVELS -> editor.putString("STATUS", "complete")
        }
        editor.commit()
    }

    fun saveState()
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        val editor = prefs.edit()
        Persistency(theGame).saveState(editor)
        editor.commit()
    }

    fun saveUpgrades()
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        val editor = prefs.edit()
        Persistency(theGame).saveUpgrades(editor)
        editor.commit()
    }

    fun saveThumbnail(level: Int)
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        val editor = prefs.edit()
        Persistency(theGame).saveThumbnailOfLevel(editor, level)
        editor.commit()
    }

    private fun loadState()
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        Persistency(theGame).loadState(prefs)
    }

    fun loadGlobalData(): Game.GlobalData
    /* retrieve some global game data, such as total number of coins.
    Saving is done in saveState().
     */
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        return Persistency(theGame).loadGlobalData(prefs)
    }

    fun loadLevelData(series: Int): HashMap<Int, Stage.Summary>
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        return Persistency(theGame).loadLevelSummaries(prefs, series) ?: HashMap()
    }

    fun loadUpgrades(): HashMap<Hero.Type, Hero>
    {
        val prefs = getSharedPreferences(getString(R.string.pref_filename), MODE_PRIVATE)
        return Persistency(theGame).loadUpgrades(prefs)
    }
}