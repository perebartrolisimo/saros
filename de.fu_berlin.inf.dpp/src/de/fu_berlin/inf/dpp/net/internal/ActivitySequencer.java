/*
 * DPP - Serious Distributed Pair Programming
 * (c) Freie Universitaet Berlin - Fachbereich Mathematik und Informatik - 2006
 * (c) Riad Djemili - 2006
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 1, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package de.fu_berlin.inf.dpp.net.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;
import org.eclipse.core.runtime.IPath;

import de.fu_berlin.inf.dpp.User;
import de.fu_berlin.inf.dpp.activities.IActivity;
import de.fu_berlin.inf.dpp.activities.TextEditActivity;
import de.fu_berlin.inf.dpp.activities.TextSelectionActivity;
import de.fu_berlin.inf.dpp.activities.ViewportActivity;
import de.fu_berlin.inf.dpp.net.ITransmitter;
import de.fu_berlin.inf.dpp.net.JID;
import de.fu_berlin.inf.dpp.net.TimedActivity;
import de.fu_berlin.inf.dpp.project.ISharedProject;
import de.fu_berlin.inf.dpp.util.AutoHashMap;
import de.fu_berlin.inf.dpp.util.Util;

/**
 * The ActivitySequencer is responsible for making sure that activities are sent
 * and received in the right order.
 * 
 * TODO Remove the dependency of this class on the ConcurrentDocumentManager,
 * push all responsibility up a layer into the SharedProject
 * 
 * @author rdjemili
 * @author coezbek
 * @author marrin
 */
public class ActivitySequencer {

    private static Logger log = Logger.getLogger(ActivitySequencer.class
        .getName());

    /**
     * Number of milliseconds between each flushing and sending of outgoing
     * activities, and testing for too old queued incoming activities.
     */
    protected static final int MILLIS_UPDATE = 1000;

    public static class QueueItem {

        public final List<User> recipients;
        public final IActivity activity;

        public QueueItem(List<User> recipients, IActivity activity) {
            this.recipients = recipients;
            this.activity = activity;
        }

        public QueueItem(User host, IActivity transformedActivity) {
            this(Collections.singletonList(host), transformedActivity);
        }
    }

    /** Buffer for outgoing activities. */
    protected final BlockingQueue<QueueItem> outgoingQueue = new LinkedBlockingQueue<QueueItem>();

    /**
     * A priority queue for timed activities.
     * 
     * TODO "Timestamps" are treated more like consecutive sequence numbers, so
     * may be all names and documentation should be changed to reflect this.
     */
    protected class ActivityQueue {

        /** How long to wait until ignore missing activities in milliseconds. */
        protected static final long ACTIVITY_TIMEOUT = 30 * 1000;

        /**
         * Sequence numbers for outgoing and incoming activities start with this
         * value.
         */
        protected static final int FIRST_SEQUENCE_NUMBER = 0;

        /** This {@link ActivityQueue} is for this user. */
        protected final JID jid;

        /** Sequence number this user sends next. */
        protected int nextSequenceNumber = FIRST_SEQUENCE_NUMBER;

        /** Sequence number expected from the next activity. */
        protected int expectedSequenceNumber = FIRST_SEQUENCE_NUMBER;

        /**
         * Oldest local timestamp for the queued activities or 0 if there are no
         * activities queued.
         * 
         * TODO Is this documentation correct?
         */
        protected long oldestLocalTimestamp = Long.MAX_VALUE;

        /** Queue of activities received. */
        protected final PriorityQueue<TimedActivity> queuedActivities = new PriorityQueue<TimedActivity>();

        /**
         * History of activities sent.
         * 
         * TODO Not really used at the moment. File creation activities don't
         * store the content at the time they were sent, so they can't be
         * re-send.
         */
        protected final List<TimedActivity> history = new LinkedList<TimedActivity>();

        public ActivityQueue(JID jid) {
            this.jid = jid;
        }

        /**
         * Create a {@link TimedActivity} and add it to the history of created
         * activities.
         */
        public TimedActivity createTimedActivity(IActivity activity) {

            TimedActivity result = new TimedActivity(activity, localJID,
                nextSequenceNumber++);
            history.add(result);
            return result;
        }

        /**
         * Add a received activity to the priority queue.
         */
        public void add(TimedActivity activity) {

            // Ignore activities with sequence numbers we have already seen or
            // don't expect anymore.
            if (activity.getSequenceNumber() < expectedSequenceNumber) {
                log.warn("Ignored activity. Expected Nr. "
                    + expectedSequenceNumber + ", got: " + activity);
                return;
            }

            long now = System.currentTimeMillis();
            activity.setLocalTimestamp(now);
            if (oldestLocalTimestamp == Long.MAX_VALUE) {
                oldestLocalTimestamp = now;
            }

            // Log debug message if there are queued activities.
            int size = queuedActivities.size();
            if (size > 0) {
                log.debug("For " + jid + " there are " + size
                    + " activities queued. First queued: "
                    + queuedActivities.peek() + ", expected nr: "
                    + expectedSequenceNumber);
            }

            queuedActivities.add(activity);
        }

        /**
         * Set {@link ActivityQueue#oldestLocalTimestamp} to the oldest local
         * timestamp of the queued activities or 0 if the queue is empty.
         */
        protected void updateOldestLocalTimestamp() {
            oldestLocalTimestamp = Long.MAX_VALUE;
            for (TimedActivity timedActivity : queuedActivities) {
                long localTimestamp = timedActivity.getLocalTimestamp();
                if (localTimestamp < oldestLocalTimestamp) {
                    oldestLocalTimestamp = localTimestamp;
                }
            }
        }

        /**
         * @return The next activity if there is one and it carries the expected
         *         sequence number, otherwise <code>null</code>.
         */
        public TimedActivity removeNext() {

            if (!queuedActivities.isEmpty()
                && queuedActivities.peek().getSequenceNumber() == expectedSequenceNumber) {

                expectedSequenceNumber++;
                TimedActivity result = queuedActivities.remove();
                updateOldestLocalTimestamp();
                return result;
            }
            return null;
        }

        /**
         * Check for activities that are missing for more than
         * {@link ActivityQueue#ACTIVITY_TIMEOUT} milliseconds or twice as long
         * if there is a file transfer for the JID of this queue, and skip an
         * expected sequence number.
         */
        protected void checkForMissingActivities() {

            if (queuedActivities.isEmpty())
                return;

            int firstQueuedSequenceNumber = queuedActivities.peek()
                .getSequenceNumber();

            // Discard all activities which we are no longer waiting for
            while (firstQueuedSequenceNumber < expectedSequenceNumber) {

                TimedActivity activity = queuedActivities.remove();

                log.error("Expected activity #" + expectedSequenceNumber
                    + " but an older activity is still in the queue"
                    + " and will be dropped (#" + firstQueuedSequenceNumber
                    + "): " + activity);

                if (queuedActivities.isEmpty())
                    return;

                firstQueuedSequenceNumber = queuedActivities.peek()
                    .getSequenceNumber();
            }

            if (firstQueuedSequenceNumber == expectedSequenceNumber)
                return; // Next Activity is ready to be executed

            /*
             * Last case: firstQueuedSequenceNumber > expectedSequenceNumber
             * 
             * -> Check for time-out
             */
            long age = System.currentTimeMillis() - oldestLocalTimestamp;
            if (age > ACTIVITY_TIMEOUT) {
                if (age < ACTIVITY_TIMEOUT * 2) {
                    // Early exit if there is a file transfer running.
                    if (transferManager.isReceiving(jid)) {
                        // TODO SS need to be more flexible
                        return;
                    }
                }

                int skipCount = firstQueuedSequenceNumber
                    - expectedSequenceNumber;
                log.warn("Gave up waiting for activity # "
                    + expectedSequenceNumber
                    + ((skipCount == 1) ? "" : " to "
                        + (firstQueuedSequenceNumber - 1)) + " from " + jid);
                expectedSequenceNumber = firstQueuedSequenceNumber;
                updateOldestLocalTimestamp();
            }
        }

        /**
         * Returns all activities which can be executed. If there are none, an
         * empty List is returned.
         * 
         * This method also checks for missing activities and discards out-dated
         * or unwanted activities.
         */
        public List<TimedActivity> removeActivities() {

            checkForMissingActivities();

            ArrayList<TimedActivity> result = new ArrayList<TimedActivity>();

            TimedActivity activity;
            while ((activity = removeNext()) != null) {
                result.add(activity);
            }

            return result;
        }
    }

    /**
     * This class manages a {@link ActivityQueue} for each other user of a
     * session.
     */
    protected class ActivityQueuesManager {
        protected final Map<JID, ActivityQueue> jid2queue = new ConcurrentHashMap<JID, ActivityQueue>();

        /**
         * Get the {@link ActivityQueue} for the given {@link JID}.
         * 
         * If there is no queue for the {@link JID}, a new one is created.
         * 
         * @param jid
         *            {@link JID} to get the queue for.
         * @return the {@link ActivityQueue} for the given {@link JID}.
         */
        protected synchronized ActivityQueue getActivityQueue(JID jid) {
            ActivityQueue queue = jid2queue.get(jid);
            if (queue == null) {
                queue = new ActivityQueue(jid);
                jid2queue.put(jid, queue);
            }
            return queue;
        }

        /**
         * @see ActivitySequencer#createTimedActivities(JID, List)
         */
        public synchronized List<TimedActivity> createTimedActivities(
            JID recipient, List<IActivity> activities) {

            ArrayList<TimedActivity> result = new ArrayList<TimedActivity>(
                activities.size());
            ActivityQueue queue = getActivityQueue(recipient);
            for (IActivity activity : activities) {
                result.add(queue.createTimedActivity(activity));
            }
            return result;
        }

        /**
         * Adds a received {@link TimedActivity}. There must be a source set on
         * the activity.
         * 
         * @param timedActivity
         *            to add to the qeues.
         * 
         * @throws IllegalArgumentException
         *             if the source of the activity is <code>null</code>.
         */
        public void add(TimedActivity timedActivity) {
            getActivityQueue(timedActivity.getSender()).add(timedActivity);
        }

        /**
         * Remove the queue for a given user.
         * 
         * @param jid
         *            of the user to remove.
         */
        public void removeQueue(JID jid) {
            jid2queue.remove(jid);
        }

        /**
         * @return all activities that can be executed. If there are none, an
         *         empty List is returned.
         * 
         *         This method also checks for missing activities and discards
         *         out-dated or unwanted activities.
         */
        public List<TimedActivity> removeActivities() {
            ArrayList<TimedActivity> result = new ArrayList<TimedActivity>();
            for (ActivityQueue queue : jid2queue.values()) {
                result.addAll(queue.removeActivities());
            }
            return result;
        }

        /**
         * @see ActivitySequencer#getActivityHistory(JID, int, boolean)
         */
        public List<TimedActivity> getHistory(JID user, int fromSequenceNumber,
            boolean andUp) {

            LinkedList<TimedActivity> result = new LinkedList<TimedActivity>();
            for (TimedActivity activity : getActivityQueue(user).history) {
                if (activity.getSequenceNumber() >= fromSequenceNumber) {
                    result.add(activity);
                    if (!andUp) {
                        break;
                    }
                }
            }
            return result;
        }

        /**
         * @see ActivitySequencer#getExpectedSequenceNumbers()
         */
        public Map<JID, Integer> getExpectedSequenceNumbers() {
            HashMap<JID, Integer> result = new HashMap<JID, Integer>();
            for (ActivityQueue queue : jid2queue.values()) {
                if (queue.queuedActivities.size() > 0) {
                    result.put(queue.jid, queue.expectedSequenceNumber);
                }
            }
            return result;
        }

        /**
         * Check each queue for activities that are too old and eventually skip
         * missing activities.
         */
        public void checkForMissingActivities() {
            for (ActivityQueue queue : jid2queue.values()) {
                queue.checkForMissingActivities();
            }
        }
    }

    protected final ActivityQueuesManager incomingQueues = new ActivityQueuesManager();

    /**
     * Whether this AS currently sends or receives events
     */
    protected boolean started = false;

    protected Timer flushTimer;

    protected final ISharedProject sharedProject;

    protected final ITransmitter transmitter;

    protected final JID localJID;

    protected final DataTransferManager transferManager;

    public ActivitySequencer(ISharedProject sharedProject,
        ITransmitter transmitter, DataTransferManager transferManager) {

        this.sharedProject = sharedProject;
        this.transmitter = transmitter;
        this.transferManager = transferManager;

        this.localJID = sharedProject.getLocalUser().getJID();
    }

    /**
     * Start periodical flushing and sending of outgoing activities and checking
     * for received activities that are queued for too long.
     * 
     * @throws IllegalStateException
     *             if this method is called on an already started
     *             {@link ActivitySequencer}
     * 
     * @see #stop()
     */
    public void start() {

        if (started) {
            throw new IllegalStateException();
        }

        this.flushTimer = new Timer(true);

        started = true;

        this.flushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Util.runSafeSync(log, new Runnable() {
                    public void run() {
                        flushTask();
                    }
                });
            }

            private void flushTask() {
                // Just to assert that after stop() no task is executed anymore
                if (!started)
                    return;

                List<QueueItem> activities = new ArrayList<QueueItem>(
                    outgoingQueue.size());
                outgoingQueue.drainTo(activities);

                Map<User, List<IActivity>> toSend = AutoHashMap
                    .getListHashMap();

                for (QueueItem item : activities) {
                    for (User recipient : item.recipients) {
                        toSend.get(recipient).add(item.activity);
                    }
                }

                for (Entry<User, List<IActivity>> e : toSend.entrySet()) {
                    sendActivities(e.getKey(), optimize(e.getValue()));
                }

                /*
                 * Periodically execQueues() because waiting activities might
                 * have timed-out
                 */
                transmitter.executeAsDispatch(new Runnable() {
                    public void run() {
                        execQueue();
                    }
                });
            }

            /**
             * Sends given activities to given recipient.
             * 
             * @private because this method must not be called from somewhere
             *          else than this TimerTask.
             * 
             * @throws IllegalArgumentException
             *             if the recipient is the local user or the activities
             *             contain <code>null</code>.
             */
            private void sendActivities(User recipient,
                List<IActivity> activities) {

                if (recipient.isLocal()) {
                    throw new IllegalArgumentException(
                        "Sending a message to the local user is not supported");
                }

                if (activities.contains(null)) {
                    throw new IllegalArgumentException(
                        "Cannot send a null activity");
                }

                JID recipientJID = recipient.getJID();
                List<TimedActivity> timedActivities = createTimedActivities(
                    recipientJID, activities);

                log.trace("Sending Activities to " + recipientJID + ": "
                    + timedActivities);

                transmitter.sendTimedActivities(recipientJID, timedActivities);
            }
        }, 0, MILLIS_UPDATE);
    }

    /**
     * Stop periodical flushing and sending of outgoing activities and checking
     * for received activities that are queued for too long.
     * 
     * @see #start()
     */
    public void stop() {
        if (!started) {
            throw new IllegalStateException();
        }

        this.flushTimer.cancel();
        this.flushTimer = null;

        started = false;
    }

    /**
     * The central entry point for receiving Activities from the Network
     * component (either via message or data transfer, thus the following is
     * synchronized on the queue).
     * 
     * The activities are sorted (in the queue) and executed in order.
     * 
     * If an activity is missing, this method just returns and queues the given
     * activity
     */
    public void exec(TimedActivity nextActivity) {

        assert nextActivity != null;

        incomingQueues.add(nextActivity);

        if (!started) {
            log.warn("Received activity but activity"
                + " sequencer has not yet been started: " + nextActivity);
            return;
        }

        execQueue();
    }

    /**
     * executes all activities that are currently in the queue
     */
    protected void execQueue() {
        List<IActivity> activities = new ArrayList<IActivity>();
        for (TimedActivity timedActivity : incomingQueues.removeActivities()) {
            activities.add(timedActivity.getActivity());
        }
        sharedProject.exec(activities);
    }

    /**
     * Sends the given activity to the given recipients.
     */
    public void sendActivity(List<User> recipients, final IActivity activity) {

        /**
         * Short cut all messages directed at local user
         */
        ArrayList<User> toSendViaNetwork = new ArrayList<User>();
        for (User user : recipients) {
            if (user.isLocal()) {
                transmitter.executeAsDispatch(new Runnable() {
                    public void run() {
                        sharedProject.exec(Collections.singletonList(activity));
                    }
                });
            } else {
                toSendViaNetwork.add(user);
            }
        }

        this.outgoingQueue.add(new QueueItem(toSendViaNetwork, activity));
    }

    /**
     * Create {@link TimedActivity}s for the given recipient and activities and
     * add them to the history of activities for the recipient.
     * 
     * This operation is thread safe, i.e. it is guaranteed that all activities
     * get increasing, consecutive sequencer numbers, even if this method is
     * called from different threads concurrently.
     */
    protected List<TimedActivity> createTimedActivities(JID recipient,
        List<IActivity> activities) {
        return incomingQueues.createTimedActivities(recipient, activities);
    }

    /**
     * Get the activity history for given user and given timestamp.
     * 
     * If andUp is <code>true</code> all activities that are equal or greater
     * than the timestamp are returned, otherwise just the activity that matches
     * the timestamp exactly.
     * 
     * If no activity matches the criteria an empty list is returned.
     */
    public List<TimedActivity> getActivityHistory(JID user,
        int fromSequenceNumber, boolean andUp) {

        return incomingQueues.getHistory(user, fromSequenceNumber, andUp);
    }

    /**
     * Get a {@link Map} that maps the {@link JID} of users with queued
     * activities to the first missing sequence number.
     */
    public Map<JID, Integer> getExpectedSequenceNumbers() {
        return incomingQueues.getExpectedSequenceNumbers();
    }

    /**
     * This method tries to reduce the number of activities transmitted by
     * removing activities that would overwrite each other and joining
     * activities that can be send as a single activity.
     */
    private static List<IActivity> optimize(List<IActivity> toOptimize) {

        List<IActivity> result = new ArrayList<IActivity>(toOptimize.size());

        TextSelectionActivity selection = null;
        LinkedHashMap<IPath, ViewportActivity> viewport = new LinkedHashMap<IPath, ViewportActivity>();

        for (IActivity activity : toOptimize) {

            if (activity instanceof TextEditActivity) {
                TextEditActivity textEdit = (TextEditActivity) activity;
                textEdit = joinTextEdits(result, textEdit);
                result.add(textEdit);
            } else if (activity instanceof TextSelectionActivity) {
                selection = (TextSelectionActivity) activity;
            } else if (activity instanceof ViewportActivity) {
                ViewportActivity viewActivity = (ViewportActivity) activity;
                viewport.remove(viewActivity.getEditor());
                viewport.put(viewActivity.getEditor(), viewActivity);
            } else {
                result.add(activity);
            }
        }

        // only send one selection activity
        if (selection != null)
            result.add(selection);

        // Add only one viewport per editor
        for (Map.Entry<IPath, ViewportActivity> entry : viewport.entrySet()) {
            result.add(entry.getValue());
        }

        assert !result.contains(null);

        return result;
    }

    private static TextEditActivity joinTextEdits(List<IActivity> result,
        TextEditActivity textEdit) {
        if (result.size() == 0) {
            return textEdit;
        }

        IActivity lastActivity = result.get(result.size() - 1);
        if (lastActivity instanceof TextEditActivity) {
            TextEditActivity lastTextEdit = (TextEditActivity) lastActivity;

            if (((lastTextEdit.getSource() == null) || lastTextEdit.getSource()
                .equals(textEdit.getSource()))
                && (textEdit.offset == lastTextEdit.offset
                    + lastTextEdit.text.length())) {
                result.remove(lastTextEdit);
                textEdit = new TextEditActivity(lastTextEdit.getSource(),
                    lastTextEdit.offset, lastTextEdit.text + textEdit.text,
                    lastTextEdit.replacedText + textEdit.replacedText,
                    lastTextEdit.getEditor());
            }
        }

        return textEdit;
    }

    /**
     * Removes queued activities from given user.
     * 
     * TODO Maybe remove outgoing activities from {@link #outgoingQueue} too!?
     * 
     * @param jid
     *            of the user that left.
     */
    public void userLeft(JID jid) {
        incomingQueues.removeQueue(jid);
    }
}
