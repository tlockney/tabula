package net.lockney.tabula

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.lockney.tabula.core.intercom.IntercomAction
import net.lockney.tabula.core.intercom.IntercomPeer
import net.lockney.tabula.core.intercom.IntercomScreen
import net.lockney.tabula.core.intercom.IntercomUi
import net.lockney.tabula.core.tlp.IntercomMode
import kotlin.math.roundToInt

/**
 * The intercom's face (packet P9.4, ADR 0025 decision 6): the list of tablets
 * to call, an incoming ring, and the banner of a call that is up. Plain
 * framework views like the rest of the kiosk (ADR 0008), added last in the
 * activity's stack so it draws over the dashboard, the photo slideshow and the
 * screensaver alike (F-SSV-21's order for the notification cards).
 *
 * The ring and the list are full-screen, because they ask for a decision. A
 * call that is up is a **banner** at the top of the screen, not a sheet: the
 * packet keeps it on screen for the whole call over whatever is showing, so it
 * must not hide the dashboard or the screensaver behind it (review of #176).
 *
 * It decides nothing: every line comes from [IntercomScreen], and every button
 * sends one [IntercomAction] to the service, which owns the engine. Nothing
 * here plays or captures audio; the ring is silent, and a tone is not part of
 * this packet.
 */
class IntercomScreenView(context: Context) : FrameLayout(context) {
    private val column: LinearLayout
    private val title: TextView
    private val subtitle: TextView
    private val detail: TextView
    private val note: TextView
    private val peers: LinearLayout
    private val list: ScrollView
    private val buttons: LinearLayout

    init {
        setBackgroundColor(SHEET)
        isClickable = true
        visibility = View.GONE

        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        title = line(context, size = 40f, bold = true)
        subtitle = line(context, size = 30f)
        detail = line(context, size = 20f)
        note = line(context, size = 18f).apply { setTextColor(Color.parseColor("#FFD180")) }

        peers = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        list = ScrollView(context).apply { addView(peers) }
        column.addView(title)
        column.addView(subtitle)
        column.addView(detail)
        column.addView(note)
        column.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(buttons)
    }

    /** True while the overlay covers the kiosk: a tap on it is not "idle". */
    val isShowing: Boolean get() = visibility == View.VISIBLE

    /** True while a call that is up shows as a banner: the screen behind it stays usable. */
    val isBanner: Boolean get() = isShowing && banner

    private var banner = false

    /** Draw one state; [IntercomScreen.Closed] takes the overlay off the screen. */
    fun render(screen: IntercomScreen) {
        if (screen is IntercomScreen.Closed) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        peers.removeAllViews()
        buttons.removeAllViews()
        banner = screen is IntercomScreen.InCall
        // a sheet asks for an answer; a banner leaves the rest of the screen
        // usable, so only the sheet takes the window's clicks
        setBackgroundColor(if (banner) BANNER else SHEET)
        isClickable = !banner
        column.gravity = if (banner) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        column.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            if (banner) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
        )
        list.visibility = if (banner) View.GONE else View.VISIBLE

        when (screen) {
            IntercomScreen.Closed -> Unit

            is IntercomScreen.Directory -> {
                title.text = "Intercom"
                subtitle.text = ""
                detail.text = if (screen.canTalk) "Who to call" else ""
                showNote(screen.note)
                for (peer in screen.peers) {
                    peers.addView(peerButton(peer, enabled = screen.canTalk), LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) })
                }
                if (screen.canTalk) {
                    button("Broadcast") { send(IntercomAction.Broadcast) }
                }
                button("Close") { send(IntercomAction.CloseDirectory) }
            }

            is IntercomScreen.Ringing -> {
                title.text = if (screen.mode == IntercomMode.BROADCAST) {
                    "Incoming broadcast"
                } else {
                    "Incoming call"
                }
                subtitle.text = screen.callerName
                detail.text = "Ends in ${IntercomUi.clockOf(screen.secondsLeft * 1000L)}"
                showNote(if (screen.cannotTalkBack) IntercomUi.CANNOT_TALK_BACK else null)
                button("Answer") { send(IntercomAction.Answer) }
                button("Decline") { send(IntercomAction.Decline) }
            }

            is IntercomScreen.InCall -> {
                title.text = screen.title
                subtitle.text = screen.peerName
                detail.text = "${screen.durationText} · ${screen.remainingText} left"
                showNote(
                    when {
                        screen.cannotTalkBack -> IntercomUi.CANNOT_TALK_BACK
                        else -> screen.note
                    },
                )
                if (screen.canReply) button("Reply") { send(IntercomAction.Reply) }
                button(
                    if (screen.mode == IntercomMode.BROADCAST && screen.canReply) {
                        "Stop listening"
                    } else {
                        "Hang up"
                    },
                ) { send(IntercomAction.HangUp) }
            }
        }
    }

    private fun showNote(text: String?) {
        note.text = text.orEmpty()
        note.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * One tablet in the list. The label is the room (or the device's name when
     * it has no area); a tablet already in a call and one that can only listen
     * both say so before anyone taps. With no microphone of its own the list is
     * shown but not tappable, because a tap could only be refused (review of #176).
     */
    private fun peerButton(peer: IntercomPeer, enabled: Boolean): Button = Button(context).apply {
        text = buildString {
            append(peer.label)
            if (!peer.canTalk) append(" — listens only")
            if (peer.inCall) append(" — in a call")
        }
        textSize = 24f
        isAllCaps = false
        isEnabled = enabled
        setOnClickListener { send(IntercomAction.Call(peer)) }
    }

    private fun button(label: String, action: () -> Unit): Button {
        val view = Button(context).apply {
            text = label
            textSize = 22f
            isAllCaps = false
            setOnClickListener { action() }
        }
        buttons.addView(view, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8); marginEnd = dp(8) })
        return view
    }

    private fun send(action: IntercomAction) {
        // the service owns the engine and acts; the screen only asks (P9.4)
        TabulaStatus.intercomAction.tryEmit(action)
    }

    private fun line(context: Context, size: Float, bold: Boolean = false): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(4), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** The list and the ring cover the screen: 95 percent opaque black. */
        const val SHEET = 0xF2000000.toInt()

        /**
         * A call's banner: dark enough to read white text over a screen, clear
         * enough that what is behind it still shows (review of #176).
         */
        const val BANNER = 0xB3000000.toInt()
    }
}
