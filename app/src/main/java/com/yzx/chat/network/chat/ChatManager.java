package com.yzx.chat.network.chat;

import android.text.TextUtils;

import com.yzx.chat.network.chat.extra.VideoMessage;
import com.yzx.chat.util.LogUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.rong.imlib.IRongCallback;
import io.rong.imlib.RongIMClient;
import io.rong.imlib.model.Conversation;
import io.rong.imlib.model.Message;
import io.rong.imlib.model.MessageContent;
import io.rong.message.FileMessage;
import io.rong.message.ImageMessage;
import io.rong.message.LocationMessage;
import io.rong.message.TextMessage;
import io.rong.message.VoiceMessage;

/**
 * Created by YZX on 2017年12月31日.
 * 每一个不曾起舞的日子 都是对生命的辜负
 */


public class ChatManager {

    private RongIMClient mRongIMClient;
    private IMClient.SubManagerCallback mSubManagerCallback;
    private SendMessageCallbackWrapper mSendMessageCallbackWrapper;
    private Map<OnChatMessageReceiveListener, String> mMessageListenerMap;
    private Map<OnMessageSendListener, String> mMessageSendStateChangeListenerMap;
    private List<OnChatMessageUnreadCountChangeListener> mChatMessageUnreadCountChangeListeners;

    private volatile int mUnreadChatMessageCount;
    private final Object mUpdateChatUnreadCountLock = new Object();

    ChatManager(IMClient.SubManagerCallback subManagerCallback) {
        if (subManagerCallback == null) {
            throw new NullPointerException("subManagerCallback can't be NULL");
        }
        mSubManagerCallback = subManagerCallback;
        mRongIMClient = RongIMClient.getInstance();
        mSendMessageCallbackWrapper = new SendMessageCallbackWrapper();
        mMessageListenerMap = new HashMap<>();
        mMessageSendStateChangeListenerMap = new HashMap<>();
        mChatMessageUnreadCountChangeListeners = Collections.synchronizedList(new LinkedList<OnChatMessageUnreadCountChangeListener>());
    }

    public List<Message> getHistoryMessages(Conversation conversation, int oldestMessageId, int count) {
        return mRongIMClient.getHistoryMessages(conversation.getConversationType(), conversation.getTargetId(), oldestMessageId, count);
    }

    public void asyncGetHistoryMessages(Conversation conversation, int oldestMessageId, int count, RongIMClient.ResultCallback<List<Message>> callback) {
        mRongIMClient.getHistoryMessages(conversation.getConversationType(), conversation.getTargetId(), oldestMessageId, count, callback);
    }

    public void sendMessage(Message message) {
        MessageContent content = message.getContent();
        if (content instanceof TextMessage || content instanceof VoiceMessage) {
            mRongIMClient.sendMessage(message, null, null, (IRongCallback.ISendMessageCallback) mSendMessageCallbackWrapper);
        } else if (content instanceof ImageMessage || content instanceof FileMessage || content instanceof VideoMessage) {
            mRongIMClient.sendMediaMessage(message, null, null, mSendMessageCallbackWrapper);
        } else if (content instanceof LocationMessage) {
            mRongIMClient.sendLocationMessage(message, null, null, mSendMessageCallbackWrapper);
        }
    }

    public void setVoiceMessageAsListened(Message message) {
        Message.ReceivedStatus status = message.getReceivedStatus();
        if (status.isListened()) {
            return;
        }
        status.setListened();
        mRongIMClient.setMessageReceivedStatus(message.getMessageId(), status, null);
    }

    public int getChatUnreadCount() {
        return mUnreadChatMessageCount;
    }

    public void updateChatUnreadCount() {
        mRongIMClient.getConversationList(new RongIMClient.ResultCallback<List<Conversation>>() {
            @Override
            public void onSuccess(List<Conversation> conversations) {
                if (conversations == null || conversations.size() == 0) {
                    updateUnreadCount(0);
                    return;
                }
                Iterator<Conversation> it = conversations.iterator();
                while (it.hasNext()) {
                    Conversation conversation = it.next();
                    if (conversation.getNotificationStatus() == Conversation.ConversationNotificationStatus.DO_NOT_DISTURB) {
                        it.remove();
                    }
                }
                if (conversations.size() == 0) {
                    updateUnreadCount(0);
                    return;
                }

                Conversation[] conversationArray = new Conversation[conversations.size()];
                conversations.toArray(conversationArray);
                mRongIMClient.getTotalUnreadCount(new RongIMClient.ResultCallback<Integer>() {
                    @Override
                    public void onSuccess(Integer integer) {
                        updateUnreadCount(integer);
                    }

                    @Override
                    public void onError(RongIMClient.ErrorCode errorCode) {
                        LogUtil.e(errorCode.getMessage());
                    }
                }, conversationArray);

            }

            @Override
            public void onError(RongIMClient.ErrorCode errorCode) {

            }

            public void updateUnreadCount(int newCount) {
                synchronized (mUpdateChatUnreadCountLock) {
                    if (mUnreadChatMessageCount != newCount) {
                        mUnreadChatMessageCount = newCount;
                        for (OnChatMessageUnreadCountChangeListener listener : mChatMessageUnreadCountChangeListeners) {
                            listener.onChatMessageUnreadCountChange(mUnreadChatMessageCount);
                        }
                    }
                }
            }
        }, IMClient.SUPPORT_CONVERSATION_TYPE);

    }

    public void addOnMessageReceiveListener(OnChatMessageReceiveListener listener, String conversationID) {
        if (!mMessageListenerMap.containsKey(listener)) {
            mMessageListenerMap.put(listener, conversationID);
        }
    }

    public void removeOnMessageReceiveListener(OnChatMessageReceiveListener listener) {
        mMessageListenerMap.remove(listener);
    }

    public void addOnMessageSendStateChangeListener(OnMessageSendListener listener, String conversationID) {
        if (!mMessageSendStateChangeListenerMap.containsKey(listener)) {
            mMessageSendStateChangeListenerMap.put(listener, conversationID);
        }
    }

    public void removeOnMessageSendStateChangeListener(OnMessageSendListener listener) {
        mMessageSendStateChangeListenerMap.remove(listener);
    }

    public void addChatMessageUnreadCountChangeListener(OnChatMessageUnreadCountChangeListener listener) {
        if (!mChatMessageUnreadCountChangeListeners.contains(listener)) {
            mChatMessageUnreadCountChangeListeners.add(listener);
        }
    }

    public void removeChatMessageUnreadCountChangeListener(OnChatMessageUnreadCountChangeListener listener) {
        mChatMessageUnreadCountChangeListeners.remove(listener);
    }

    void destroy() {
        mMessageListenerMap.clear();
        mMessageSendStateChangeListenerMap.clear();
        mChatMessageUnreadCountChangeListeners.clear();
        mMessageListenerMap = null;
        mMessageSendStateChangeListenerMap = null;
        mChatMessageUnreadCountChangeListeners = null;
    }

    void onReceiveContactNotificationMessage(Message message, int untreatedCount) {
        if (mMessageListenerMap.size() == 0) {
            return;
        }
        OnChatMessageReceiveListener chatListener;
        String conversationID;
        for (Map.Entry<OnChatMessageReceiveListener, String> entry : mMessageListenerMap.entrySet()) {
            conversationID = entry.getValue();
            chatListener = entry.getKey();
            if (chatListener == null) {
                continue;
            }
            if (TextUtils.isEmpty(conversationID) || conversationID.equals(message.getTargetId())) {
                chatListener.onChatMessageReceived(message, untreatedCount);
            }
        }
    }

    private class SendMessageCallbackWrapper extends RongIMClient.SendImageMessageCallback implements IRongCallback.ISendMediaMessageCallback {

        @Override
        public void onAttached(Message message) {
            String conversationID = message.getTargetId();
            for (Map.Entry<OnMessageSendListener, String> entry : mMessageSendStateChangeListenerMap.entrySet()) {
                if (conversationID.equals(entry.getValue()) || entry.getValue() == null) {
                    entry.getKey().onAttached(message);
                }
            }
        }

        @Override
        public void onProgress(Message message, int i) {
            String conversationID = message.getTargetId();
            for (Map.Entry<OnMessageSendListener, String> entry : mMessageSendStateChangeListenerMap.entrySet()) {
                if (conversationID.equals(entry.getValue()) || entry.getValue() == null) {
                    entry.getKey().onProgress(message, i);
                }
            }
        }

        @Override
        public void onSuccess(Message message) {
            String conversationID = message.getTargetId();
            for (Map.Entry<OnMessageSendListener, String> entry : mMessageSendStateChangeListenerMap.entrySet()) {
                if (conversationID.equals(entry.getValue()) || entry.getValue() == null) {
                    entry.getKey().onSuccess(message);
                }
            }
        }

        @Override
        public void onError(Message message, RongIMClient.ErrorCode errorCode) {
            LogUtil.e("send message fail:" + errorCode);
            String conversationID = message.getTargetId();
            for (Map.Entry<OnMessageSendListener, String> entry : mMessageSendStateChangeListenerMap.entrySet()) {
                if (conversationID.equals(entry.getValue()) || entry.getValue() == null) {
                    entry.getKey().onError(message);
                }
            }
        }

        @Override
        public void onCanceled(Message message) {
            String conversationID = message.getTargetId();
            for (Map.Entry<OnMessageSendListener, String> entry : mMessageSendStateChangeListenerMap.entrySet()) {
                if (conversationID.equals(entry.getValue()) || entry.getValue() == null) {
                    entry.getKey().onCanceled(message);
                }
            }
        }
    }

    public interface OnMessageSendListener {

        void onAttached(Message message);

        void onProgress(Message message, int progress);

        void onSuccess(Message message);

        void onError(Message message);

        void onCanceled(Message message);
    }

    public interface OnChatMessageUnreadCountChangeListener {
        void onChatMessageUnreadCountChange(int count);
    }

    public interface OnChatMessageReceiveListener {
        void onChatMessageReceived(Message message, int untreatedCount);
    }


}
