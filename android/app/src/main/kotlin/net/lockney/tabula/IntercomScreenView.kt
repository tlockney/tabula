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
 * It decides nothing: every line comes from [IntercomScreen], and every button
 * sends one [IntercomAction] to the service, which owns the engine. Nothing
 * here plays or captures audio; the ring is silent, and a tone is not part of
 * this packet.
 */
class IntercomScreenView(context: Context) : FrameLayout(context) {
    private val title: TextView
    private val subtitle: TextView
    private val detail: TextView
    private val note: TextView
    private val peers: LinearLayout
    private val buttons: LinearLayout

    init {
        setBackgroundColor(SHEET)
        isClickable = true
        visibility = View.GONE

        val column = LinearLayout(context).apply {
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
        val list = ScrollView(context).apply { addView(peers) }
        column.addView(title)
        column.addView(subtitle)
        column.addView(detail)
        column.addView(note)
        column.addView(
            list,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            },
        )
        buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(buttons)
    }

    /** True while the overlay covers the kiosk: a tap on it is not "idle". */
    val isShowing: Boolean get() = visibility == View.VISIBLE

    /** Draw one state; [IntercomScreen.Closed] takes the overlay off the screen. */
    fun render(screen: IntercomScreen) {
        if (screen is IntercomScreen.Closed) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        peers.removeAllViews()
        buttons.removeAllViews()
        when (screen) {
            IntercomScreen.Closed -> Unit

            is IntercomScreen.Directory -> {
                title.text = "Intercom"
                subtitle.text = ""
                detail.text = if (screen.canTalk) "Who to call" else ""
                note.text = screen.note.orEmpty()
                note.visibility = if (screen.note == null) View.GONE else View.VISIBLE
                for (peer in screen.peers) {
                    peers.addView(peerButton(peer), LinearLayout.LayoutParams(
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
                note.text = if (screen.cannotTalkBack) IntercomUi.CANNOT_TALK_BACK else ""
                note.visibility = if (screen.cannotTalkBack) View.VISIBLE else View.GONE
                button("Answer") { send(IntercomAction.Answer) }
                button("Decline") { send(IntercomAction.Decline) }
            }

            is IntercomScreen.InCall -> {
                title.text = screen.title
                subtitle.text = screen.peerName
                detail.text = "${screen.durationText} · ${screen.remainingText} left"
                note.text = when {
                    screen.cannotTalkBack -> IntercomUi.CANNOT_TALK_BACK
                    screen.note != null -> screen.note
                    else -> ""
                }
                note.visibility = if (note.text.isNotEmpty()) View.VISIBLE else View.GONE
                if (screen.canReply) button("Reply") { send(IntercomAction.Reply) }
                button(if (screen.mode == IntercomMode.BROADCAST && screen.canReply) "Stop listening" else "Hang up") {
                    send(IntercomAction.HangUp)
                }
            }
        }
    }

    private fun peerButton(peer: IntercomPeer): Button = Button(context).apply {
        text = if (peer.inCall) "${peer.label} — in a call" else peer.label
        textSize = 24f
        isAllCaps = false
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
        /** Opaque black: a call is not something to read the dashboard behind. */
        const val SHEET = 0xF2000000.toInt()
    }
}
