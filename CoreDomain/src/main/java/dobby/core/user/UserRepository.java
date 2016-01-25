package dobby.core.user;

import dobby.core.Repository;
import dobby.core.app.AppRepository;
import dobby.core.stakeholder.User;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Created by gautierc on 13/12/15.
 */
public class UserRepository implements Repository<Object, User> {

    private final static Logger LOGGER = Logger.getLogger(UserRepository.class.getName());

    private final static int PENDING_MINUTES = 2;
    private final static int MAX_PENDING_USR = 20;
    private final static int GC_CYCLE_MS = 1000;

    private final Map<Object, User> entries = Collections.synchronizedMap(new HashMap<>());
    private final Queue<User> pending = new ConcurrentLinkedQueue<>();

    private Thread garbageCollection;
    private AppRepository appRepo;

    public UserRepository() {
        garbageCollection = new Thread() {
            public void run() {
                while (true)
                {
                    // If pending list is empty, waiting...
                    if (pending.size() == 0)
                    {
                        try {
                            synchronized (this) {
                                this.wait();
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    // Freshly notified (or new iteration), we wait 1s before doing anything
                    try {
                        Thread.sleep(GC_CYCLE_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // Looking for some old user to remove
                    long timeLimit = new Date(new Date().getTime() - (PENDING_MINUTES * 60000)).getTime();
                    pending.stream().filter(user -> user.getPendingTime() < timeLimit).forEach(user -> {
                        removeFromPending(user);
                    });
                }
            }
        };
        garbageCollection.start();
    }

    public void setAppRepository(AppRepository appRepo) {
        this.appRepo = appRepo;
    }

    public AppRepository getAppRepository() {
        return this.appRepo;
    }

    @Override
    public void accept(User user) {
        // We will try to restore the previous User (if we had one) => only one connection to the front (depend on token implem)!!
        Optional<User> pendingUser = getPendingUser(user);
        if (pendingUser.isPresent()) {
            pending.remove(pendingUser.get());
            pendingUser.get().restore(user);
            user = pendingUser.get();
            LOGGER.info("User restored from pending: "+user.getName());
        } else {
            LOGGER.info("New user accepted: "+user.getName());
        }

        entries.put(user.getSession(), user);
    }

    @Override
    public void close(User user) {
        entries.remove(user.getSession());

        if (!user.hasQuit())
        {
            user.pending();
            pending.add(user);
            if (pending.size() > MAX_PENDING_USR)
                throwOldestPendingUser();
            synchronized (garbageCollection) {
                garbageCollection.notify();
            }
            LOGGER.info("User put in pending: "+user.getName());
        } else {
            LOGGER.info("User disconnected: "+user.getName());
        }
    }

    private Optional<User> getPendingUser(User newUser) {
        return pending.stream().filter(u -> u.getToken().equals(newUser.getToken())).findFirst();
    }

    @Override
    public Optional<User> getItemByKey(Object key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public Optional<User> getItemByName(String name) {
        Optional<User> item;

        item = entries.values().parallelStream().filter(u -> u.getName() == name).findFirst();
        if (!item.isPresent())
            item = pending.stream().filter(u -> u.getName() == name).findFirst();

        return item;
    }

    private void throwOldestPendingUser() {
        removeFromPending(pending.remove());
    }

    private void removeFromPending(User user) {
        user.logout();
        pending.remove(user);
        router.
        LOGGER.info("User log-out and unset: "+user.getName());
    }
}