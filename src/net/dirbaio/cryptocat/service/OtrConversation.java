package net.dirbaio.cryptocat.service;

import android.util.Log;
import net.dirbaio.cryptocat.ExceptionRunnable;
import net.java.otr4j.*;
import net.java.otr4j.session.SessionID;
import net.java.otr4j.session.SessionStatus;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import java.security.*;

/**
 * An OTR conversation.
 */
public class OtrConversation extends Conversation implements MessageListener, OtrEngineHost
{
	public final String buddyNickname;
	public final MultipartyConversation parent;
	Chat chat;

    private OtrPolicy otrPolicy;
    private SessionID otrSessionID;
    private OtrEngine otrEngine;

	public OtrConversation(MultipartyConversation parent, String buddyNickname) throws XMPPException
	{
		super(parent.server, parent.nickname);
		this.parent = parent;
		this.buddyNickname = buddyNickname;
		this.id = buddyNickname;
	}

	@Override
	public void join()
	{
		if (getState() != State.Left)
			throw new IllegalStateException("You're already joined.");
		if (server.getState() != CryptocatServer.State.Connected)
			throw new IllegalStateException("Server is not connected");
		if (parent.getState() != State.Joined)
			throw new IllegalStateException("You haven't joined the chatroom");

        setState(State.Joining);

        otrPolicy = new OtrPolicyImpl(OtrPolicy.ALLOW_V2 | OtrPolicy.ERROR_START_AKE | OtrPolicy.REQUIRE_ENCRYPTION);
        otrSessionID = new SessionID("", "", "");
        otrEngine = new OtrEngineImpl(this);

		CryptocatService.getInstance().post(new ExceptionRunnable()
		{
			@Override
			public void run() throws Exception
			{
				try
				{
					server.notifyStateChanged();
					chat = parent.muc.createPrivateChat(parent.roomName +"@"+parent.server.config.conferenceServer+"/"+buddyNickname, OtrConversation.this);

                    setState(State.Joined);
                    otrEngine.startSession(otrSessionID);
				}
				catch(Exception e)
				{
					e.printStackTrace();

                    setState(State.Error);
				}
			}
		});
	}

	@Override
	public void leave()
	{
		if (getState() == State.Left)
			throw new IllegalStateException("You have not joined.");

		final Chat chatFinal = chat;
		CryptocatService.getInstance().post(new ExceptionRunnable()
		{
			@Override
			public void run() throws Exception
			{
				chatFinal.removeMessageListener(OtrConversation.this);
			}
		});

		chat = null;

        setState(State.Left);
	}

	@Override
	public void sendMessage(final String msg) throws OtrException {
		//Check state
		if (getState() != State.Joined)
			throw new IllegalStateException("You have not joined.");

        if(otrEngine.getSessionStatus(otrSessionID) != SessionStatus.ENCRYPTED)
        {
            addMessage(new CryptocatMessage(CryptocatMessage.Type.Error, "", "Encrypted session hasn't been established yet."));
        }
        else
        {
            addMessage(new CryptocatMessage(CryptocatMessage.Type.MessageMine, nickname, msg));
            sendRawMessage(otrEngine.transformSending(otrSessionID, msg));
        }
	}

	@Override
	public void processMessage(Chat chat, final Message message)
	{
		CryptocatService.getInstance().uiPost(new ExceptionRunnable() {
            @Override
            public void run() throws Exception {
                String txt = message.getBody();
                String plaintext = otrEngine.transformReceiving(otrSessionID, txt);
                if (plaintext != null)
                    addMessage(new CryptocatMessage(CryptocatMessage.Type.Message, buddyNickname, plaintext));
            }
        });
	}

	@Override
	public String toString()
	{
		return "[" + getState() + "] " + parent.roomName +":"+buddyNickname;
	}

    private void sendRawMessage(final String msg)
    {
        CryptocatService.getInstance().post(new ExceptionRunnable()
        {
            @Override
            public void run() throws Exception
            {
                chat.sendMessage(msg);
            }
        });
    }

    @Override
    public void injectMessage(SessionID sessionID, String s) {
        sendRawMessage(s);
    }

    @Override
    public void showWarning(SessionID sessionID, String s) {
        Log.w("OTR", "OTR WARNING: " + s);
    }

    @Override
    public void showError(SessionID sessionID, String s) {
        Log.e("OTR", "OTR ERROR: " + s);
    }

    @Override
    public OtrPolicy getSessionPolicy(SessionID sessionID) {
        return otrPolicy;
    }

    @Override
    public KeyPair getKeyPair(SessionID sessionID) {
        KeyPairGenerator kg;
        try {
            kg = KeyPairGenerator.getInstance("DSA");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }

        return kg.genKeyPair();
    }
}
