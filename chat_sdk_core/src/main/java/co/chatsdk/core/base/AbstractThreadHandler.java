package co.chatsdk.core.base;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.maps.model.LatLng;

import org.joda.time.DateTime;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import co.chatsdk.core.dao.DaoCore;
import co.chatsdk.core.dao.Keys;
import co.chatsdk.core.dao.Message;
import co.chatsdk.core.dao.Thread;
import co.chatsdk.core.dao.User;
import co.chatsdk.core.dao.UserThreadLink;
import co.chatsdk.core.dao.UserThreadLinkDao;
import co.chatsdk.core.dao.sorter.ThreadsSorter;
import co.chatsdk.core.defines.FirebaseDefines;
import co.chatsdk.core.handlers.CoreHandler;
import co.chatsdk.core.handlers.ThreadHandler;
import co.chatsdk.core.interfaces.ThreadType;
import co.chatsdk.core.rx.ObservableConnector;
import co.chatsdk.core.session.ChatSDK;
import co.chatsdk.core.session.NM;
import co.chatsdk.core.session.StorageManager;
import co.chatsdk.core.types.Defines;
import co.chatsdk.core.types.FileUploadResult;
import co.chatsdk.core.types.MessageSendProgress;
import co.chatsdk.core.types.MessageSendStatus;
import co.chatsdk.core.types.MessageType;
import co.chatsdk.core.utils.GoogleUtils;
import co.chatsdk.core.utils.ImageUtils;
import co.chatsdk.core.utils.StringChecker;
import id.zelory.compressor.Compressor;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;


/**
 * Created by benjaminsmiley-andrews on 25/05/2017.
 */

public abstract class AbstractThreadHandler implements ThreadHandler {

    public Single<List<Message>> loadMoreMessagesForThread(final Message fromMessage, final Thread thread) {
        return Single.create(new SingleOnSubscribe<List<Message>>() {
            @Override
            public void subscribe(final SingleEmitter<List<Message>> e) throws Exception {

                Date messageDate = fromMessage != null ? fromMessage.getDate().toDate() : new Date();

                // First try to load the messages from the database
                List<Message> list = StorageManager.shared().fetchMessagesForThreadWithID(thread.getId(), FirebaseDefines.NumberOfMessagesPerBatch + 1, messageDate);
                e.onSuccess(list);
            }
        }).subscribeOn(Schedulers.single());
    }

    /**
     * Preparing a text message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * The message will be received before sending in the onMainFinished Callback with a Status that its in the sending process.
     * When the message is fully sent the status will be changed and the onItem callback will be invoked.
     * When done or when an error occurred the calling method will be notified.
     */
    public Observable<MessageSendProgress> sendMessageWithText(final String text, final Thread thread) {
        return Observable.create(new ObservableOnSubscribe<MessageSendProgress>() {
            @Override
            public void subscribe(final ObservableEmitter<MessageSendProgress> e) throws Exception {

                final Message message = newMessage(MessageType.Text, thread);
                message.setTextString(text);

                e.onNext(new MessageSendProgress(message));

                ObservableConnector<MessageSendProgress> connector = new ObservableConnector<>();
                connector.connect(implSendMessage(message), e);

            }
        }).subscribeOn(Schedulers.single());

    }

    public static Message newMessage (MessageType type, Thread thread) {
        Message message = new Message();
        DaoCore.createEntity(message);
        message.setSender(NM.currentUser());
        message.setMessageStatus(MessageSendStatus.Sending);
        message.setDate(new DateTime(System.currentTimeMillis()));
        message.setEntityID(UUID.randomUUID().toString());
        message.setMessageType(type);
        thread.addMessage(message);
        return message;
    }

    /**
     * Preparing a location message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * When done or when an error occurred the calling method will be notified.
     *
     * @param filePath     is a String representation of a bitmap that contain the image of the location wanted.
     * @param location     is the Latitude and Longitude of the picked location.
     * @param thread       the thread that the message is sent to.
     */
    public Observable<MessageSendProgress> sendMessageWithLocation(final String filePath, final LatLng location, final Thread thread) {
        return Observable.create(new ObservableOnSubscribe<MessageSendProgress>() {
            @Override
            public void subscribe(ObservableEmitter<MessageSendProgress> e) throws Exception {
                final Message message = newMessage(MessageType.Location, thread);

                int maxSize = ChatSDK.config().imageMaxThumbnailDimension;
                String imageURL = GoogleUtils.getMapImageURL(location, maxSize, maxSize);

                // Add the LatLng data to the message and the image url and thumbnail url
                // TODO: Deprecated
                message.setTextString(String.valueOf(location.latitude)
                        + Defines.DIVIDER
                        + String.valueOf(location.longitude)
                        + Defines.DIVIDER + imageURL
                        + Defines.DIVIDER + imageURL
                        + Defines.DIVIDER + ImageUtils.getDimensionAsString(maxSize, maxSize));

                message.setValueForKey(location.longitude, Keys.MessageLongitude);
                message.setValueForKey(location.latitude, Keys.MessageLatitude);
                message.setValueForKey(maxSize, Keys.MessageImageWidth);
                message.setValueForKey(maxSize, Keys.MessageImageHeight);
                message.setValueForKey(imageURL, Keys.MessageImageURL);
                message.setValueForKey(imageURL, Keys.MessageThumbnailURL);

                e.onNext(new MessageSendProgress(message));

                ObservableConnector<MessageSendProgress> connector = new ObservableConnector<>();
                connector.connect(implSendMessage(message), e);

            }
        }).subscribeOn(Schedulers.single());
    }

    /**
     * Preparing an image message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * When done or when an error occurred the calling method will be notified.
     *
     * @param filePath is a file that contain the image. For now the file will be decoded to a Base64 image representation.
     * @param thread   thread that the message is sent to.
     */
    public Observable<MessageSendProgress> sendMessageWithImage(final String filePath, final Thread thread) {
        return Observable.create(new ObservableOnSubscribe<MessageSendProgress>() {
            @Override
            public void subscribe(final ObservableEmitter<MessageSendProgress> e) throws Exception {

                final Message message = newMessage(MessageType.Image, thread);

                // First pass back an empty result so that we add the cell to the table view
                message.setMessageStatus(MessageSendStatus.Uploading);
                message.update();
                e.onNext(new MessageSendProgress(message));

                File compress = new Compressor(ChatSDK.shared().context())
                        .setMaxHeight(ChatSDK.config().imageMaxHeight)
                        .setMaxWidth(ChatSDK.config().imageMaxWidth)
                        .compressToFile(new File(filePath));

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                final Bitmap image = BitmapFactory.decodeFile(compress.getPath(), options);

                if(image == null) {
                    // TODO: Localize
                    e.onError(new Throwable("Unable to save image to disk"));
                    return;
                }

                NM.upload().uploadImage(image).subscribe(new Observer<FileUploadResult>() {
                    @Override
                    public void onSubscribe(Disposable d) {}

                    @Override
                    public void onNext(FileUploadResult result) {
                        if(!StringChecker.isNullOrEmpty(result.url))  {

                            message.setTextString(result.url + Defines.DIVIDER + result.url + Defines.DIVIDER + ImageUtils.getDimensionAsString(image));

                            message.setValueForKey(image.getWidth(), Keys.MessageImageWidth);
                            message.setValueForKey(image.getHeight(), Keys.MessageImageHeight);
                            message.setValueForKey(result.url, Keys.MessageImageURL);
                            message.setValueForKey(result.url, Keys.MessageThumbnailURL);

                            message.update();

                            Timber.v("ProgressListener: " + result.progress.asFraction());

                        }

                        e.onNext(new MessageSendProgress(message, result.progress));

                    }

                    @Override
                    public void onError(Throwable ex) {
                        e.onError(ex);
                    }

                    @Override
                    public void onComplete() {

                        message.setMessageStatus(MessageSendStatus.Sending);
                        message.update();

                        e.onNext(new MessageSendProgress(message));

                        ObservableConnector<MessageSendProgress> connector = new ObservableConnector<>();
                        connector.connect(implSendMessage(message), e);

                    }
                });
            }
        }).subscribeOn(Schedulers.single());

    }

    /**
    /* Convenience method to save the message to the database then pass it to the custom network adapter
     * send method so it can be sent via the network
     */
    public Observable<MessageSendProgress> implSendMessage(final Message message) {
        return Observable.create(new ObservableOnSubscribe<MessageSendProgress>() {
            @Override
            public void subscribe(ObservableEmitter<MessageSendProgress> e) throws Exception {
                message.update();
                message.getThread().update();
                e.onNext(new MessageSendProgress(message));
                e.onComplete();
            }
        }).flatMap(new Function<MessageSendProgress, ObservableSource<MessageSendProgress>>() {
            @Override
            public ObservableSource<MessageSendProgress> apply(MessageSendProgress messageSendProgress) throws Exception {
                return handleMessageSend(message, sendMessage(message));
            }
        }).subscribeOn(Schedulers.single()).doOnComplete(new Action() {
            @Override
            public void run() throws Exception {
                Timber.v("Complete");
            }
        });
    }

    public static Observable<MessageSendProgress> handleMessageSend (final Message message, Observable<MessageSendProgress> messageSendObservable) {
        return messageSendObservable.doOnComplete(new Action() {
            @Override
            public void run() throws Exception {
                message.setMessageStatus(MessageSendStatus.Sent);
                message.update();
            }
        }).doOnError(new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                message.setMessageStatus(MessageSendStatus.Failed);
                message.update();
            }
        }).subscribeOn(Schedulers.single());
    }

    public int getUnreadMessagesAmount(boolean onePerThread){
        List<Thread> threads = getThreads(ThreadType.Private, false);

        int count = 0;
        for (Thread t : threads) {
            if (onePerThread) {
                if(!t.isLastMessageWasRead()) {
                    count++;
                }
            }
            else {
                count += t.getUnreadMessagesCount();
            }
        }
        return count;
    }

    public Single<Thread> createThread(String name, User... users) {
        return createThread(name, Arrays.asList(users));
    }

    public Single<Thread> createThread(List<User> users) {
        return createThread(null, users);
    }

    public Completable addUsersToThread(Thread thread, User... users) {
        return addUsersToThread(thread, Arrays.asList(users));
    }

    public Completable removeUsersFromThread(Thread thread, User... users) {
        return removeUsersFromThread(thread, Arrays.asList(users));
    }

    public List<Thread> getThreads(int type) {
        return getThreads(type, false);
    }

    public List<Thread> getThreads(int type, boolean allowDeleted){

        if(ThreadType.isPublic(type)) {
            return StorageManager.shared().fetchThreadsWithType(ThreadType.PublicGroup);
        }

        // We may access this method post authentication
        if(NM.currentUser() == null) {
            return new ArrayList<>();
        }

        List<UserThreadLink> links = DaoCore.fetchEntitiesWithProperty(UserThreadLink.class, UserThreadLinkDao.Properties.UserId, NM.currentUser().getId());

        List<Thread> threads = new ArrayList<>();

        // Pull the threads out of the link object . . . if only gDao supported manyToMany . . .
        for (UserThreadLink link : links) {
            if(link.getThread().typeIs(type) && (!link.getThread().getDeleted() || allowDeleted)) {
                threads.add(link.getThread());
            }
        }

        // Sort the threads list before returning
        Collections.sort(threads, new ThreadsSorter());

        return threads;
    }

    public void sendLocalSystemMessage(String text, Thread thread) {

    }

    public void sendLocalSystemMessage(String text, CoreHandler.bSystemMessageType type, Thread thread) {

    }


}
