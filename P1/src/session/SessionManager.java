package session;

import groupMembership.Server;

import java.util.Hashtable;
import java.util.Timer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.http.*;

public class SessionManager {
	private static final long expiration = 600000;
	private static final String cookiename = "CS5300PROJECT1SESSION";	
	protected static final Integer sessionTimeout = 600; // Timeout time in seconds
	// of session
	protected static final Integer sessionCleanerFrequency = 60; // Delay in
	// seconds for
	// running cleaner

	// Locks for session table
	protected static final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
	protected static final Lock readlock = rwl.readLock();
	protected static final Lock writelock = rwl.writeLock();

	// Sweeper for session cleanup
	protected static final SessionCleaner sessionCleaner = new SessionCleaner();
	protected static final Timer sessionCleanerTimer = new Timer();

	public static Session sessionRead(String SID, Integer change_count, Server s, 
			Server t, int choice) {
		Hashtable<String,Session> hashtable;
		Session sess = null;
		String key = SID+"_"+change_count+"_"+s.toString()+"-"+t.toString();
		System.out.println("Server trying to retrieve: " + SID);
		readlock.lock();
		if (choice==1) {
			hashtable = s.getHash();
		}
		else {
			hashtable = t.getHash();
		}
		sess = hashtable.get(key);
		readlock.unlock();
		return sess;
	}
	
	public static void sessionWrite(Server s, Session oldsess, Session newsess) {
		System.out.println("server adding session: " + newsess.toString());
		writelock.lock();
		Hashtable<String,Session> h = s.getHash();
		Session session = s.getHash().get(oldsess.toString());
		if (session == null) {
			h.put(newsess.toString(), newsess);
		}
		else {
			h.remove(oldsess.toString());
			h.put(newsess.toString(), newsess);
		}
		writelock.unlock();
	}
	
	public static Session getAndIncrement(HttpServletRequest request, Server s) {
		long date = System.currentTimeMillis();
		Cookie[] cookies = request.getCookies();
		Cookie cookie = null;
		Session session;
		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				Cookie c = cookies[i];
				if (c.getName().equals(cookiename)) {
					cookie = c;
				}
			}
		}
		if (cookie == null) {
			session = new Session(s,new Integer(0),"",expiration+date);
			session.setPrimary(s);
			session.setSessionnum(s.getGlobal());
			int global = s.getGlobal()+1;
			s.setGlobal(global);
		} else {
			readlock.lock();
			session = s.getHash().get(cookie.getValue());
			readlock.unlock();
			// If we are unable to get session
			if (session == null) {
				session = new Session(s,new Integer(0),"",expiration+date);
				session.setPrimary(s);
				session.setSessionnum(s.getGlobal());
				int global = s.getGlobal()+1;
				s.setGlobal(global);
			} else {
				session.setChangecount(session.getChangecount()+1);
				session.setExpiration(expiration+date);
			}
		}
		return session;
	}

	public static void putCookie(HttpServletResponse response, Session sess) {
		Cookie cookie = new Cookie(cookiename, sess.toString());
		cookie.setMaxAge(sessionTimeout);
		response.addCookie(cookie);
	}

	public static void destroyCookie(HttpServletRequest request,
			HttpServletResponse response) {
		Cookie[] cookies = request.getCookies();
		Cookie cookie = null;
		if (cookies != null) {
			for (int is = 0; is < cookies.length; is++) {
				Cookie c = cookies[is];
				if (c.getName().equals(cookiename)) {
					cookie = c;
				}
			}
		}
		if (cookie != null) {
			cookie = new Cookie(cookiename, "");
			cookie.setMaxAge(0);
			response.addCookie(cookie);
		}
	}

	public static void startCleaner() {
		sessionCleanerTimer.schedule(sessionCleaner,
				sessionCleanerFrequency * 1000, sessionCleanerFrequency * 1000);
	}

	public static void donecleanup() {
		sessionCleanerTimer.cancel();
	}
}
