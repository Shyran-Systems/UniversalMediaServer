package net.pms.remote;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import net.pms.PMS;
import net.pms.configuration.IpFilter;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.Range;
import net.pms.external.StartStopListenerDelegate;
import net.pms.newgui.LooksFrame;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteUtil {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemoteUtil.class);

	public static final String MIME_MP4 = "video/mp4";
	public static final String MIME_OGG = "video/ogg";
	public static final String MIME_WEBM = "video/webm";
	//public static final String MIME_TRANS = MIME_MP4;
	public static final String MIME_TRANS = MIME_OGG;
	//public static final String MIME_TRANS = MIME_WEBM;
	public static final String MIME_MP3 = "audio/mpeg";
	public static final String MIME_WAV = "audio/wav";
	public static final String MIME_PNG = "image/png";
	public static final String MIME_JPG = "image/jpeg";


	public static void dumpFile(String file, HttpExchange t) throws IOException {
		File f = new File(file);
		dumpFile(f, t);
	}

	public static void dumpFile(File f, HttpExchange t) throws IOException {
		LOGGER.debug("file " + f + " " + f.length());
		if (!f.exists()) {
			throw new IOException("no file");
		}
		t.sendResponseHeaders(200, f.length());
		dump(new FileInputStream(f), t.getResponseBody(), null);
		LOGGER.debug("dump of " + f.getName() + " done");
	}

	public static void dump(InputStream in, OutputStream os) throws IOException {
		dump(in, os, null);
	}

	public static void dump(final InputStream in, final OutputStream os, final StartStopListenerDelegate start) throws IOException {
		Runnable r = new Runnable() {
			@Override
			public void run() {
				byte[] buffer = new byte[32 * 1024];
				int bytes;
				int sendBytes = 0;

				try {
					while ((bytes = in.read(buffer)) != -1) {
						sendBytes += bytes;
						os.write(buffer, 0, bytes);
						os.flush();
					}
				} catch (IOException e) {
					LOGGER.trace("Sending stream with premature end: " + sendBytes + " bytes. Reason: " + e.getMessage());
				} finally {
					try {
						if (in != null) {
							in.close();
						}
					} catch (IOException e) {
					}
				}
				try {
					os.close();
				} catch (IOException e) {
				}
				if (start != null) {
					start.stop();
				}
			}
		};
		new Thread(r).start();
	}

	public static String read(String resource) {
		try {
			return IOUtils.toString(RemoteUtil.class.getResourceAsStream("/resources/web/" + resource), "UTF-8");
		} catch (IOException e) {
			LOGGER.debug("Error reading resource: " + e);
		}
		return null;
	}

	public static String read(File f) {
		try {
			return FileUtils.readFileToString(f, Charset.forName("UTF-8"));
		} catch (IOException e) {
			LOGGER.debug("Error reading file: " + e);
		}
		return null;
	}

	public static String getId(String path, HttpExchange t) {
		String id = "0";
		int pos = t.getRequestURI().getPath().indexOf(path);
		if (pos != -1) {
			id = t.getRequestURI().getPath().substring(pos + path.length());
		}
		return id;
	}

	public static String strip(String id) {
		int pos = id.lastIndexOf('.');
		if (pos != -1) {
			return id.substring(0, pos);
		}
		return id;
	}

	public static boolean deny(HttpExchange t) {
		return !PMS.getConfiguration().getIpFiltering().allowed(t.getRemoteAddress().getAddress()) ||
			   !PMS.isReady();
	}

	private static Range nullRange(long len) {
		return Range.create(0, len, 0.0, 0.0);
	}

	public static Range parseRange(Headers hdr, long len) {
		if (hdr == null) {
			return nullRange(len);
		}
		List<String> r = hdr.get("Range");
		if (r == null) { // no range
			return nullRange(len);
		}
		// assume only one
		String range = r.get(0);
		String[] tmp = range.split("=")[1].split("-");
		long start = Long.parseLong(tmp[0]);
		long end = tmp.length == 1 ? len : Long.parseLong(tmp[1]);
		return Range.create(start, end, 0.0, 0.0);
	}

	public static void sendLogo(HttpExchange t) throws IOException {
		InputStream in = LooksFrame.class.getResourceAsStream("/resources/images/logo.png");
		t.sendResponseHeaders(200, 0);
		OutputStream os = t.getResponseBody();
		dump(in, os, null);
	}

	public static boolean directmime(String mime) {
		return mime != null && (mime.equals(MIME_MP4) || mime.equals(MIME_WEBM) || mime.equals(MIME_OGG) ||
			mime.equals(MIME_MP3) || mime.equals(MIME_PNG) || mime.equals(MIME_JPG)/*|| mime.equals(MIME_WAV)*/);
	}

	public static String userName(HttpExchange t) {
		HttpPrincipal p = t.getPrincipal();
		if (p == null) {
			return "";
		}
		return p.getUsername();
	}

	public static String getQueryVars(String query, String var) {
		if (StringUtils.isEmpty(query)) {
			return null;
		}
		for (String p : query.split("&")) {
			String[] pair = p.split("=");
			if (pair[0].equalsIgnoreCase(var)) {
				if (pair.length > 1 && StringUtils.isNotEmpty(pair[1])) {
					return pair[1];
				}
			}
		}
		return null;
	}

	public static String getCookie(String name, HttpExchange t) {
		String cstr = t.getRequestHeaders().getFirst("Cookie");
		if (StringUtils.isEmpty(cstr)) {
			return null;
		}
		name += "=";
		for (String str: cstr.trim().split("\\s*;\\s*")) {
			if (str.startsWith(name)) {
				return str.substring(name.length());
			}
		}
		return null;
	}

	private static final int WIDTH = 0;
	private static final int HEIGHT = 1;

	private static final int DEFAULT_WIDTH = 720;
	private static final int DEFAULT_HEIGHT = 404;

	private static int getHW(int cfgVal, int id, int def) {
		if (cfgVal != 0) {
			// if we have a value cfg return that
			return cfgVal;
		}
		String s = PMS.getConfiguration().getWebSize();
		if (StringUtils.isEmpty(s)) {
			// no size string return default
			return def;
		}
		String[] tmp = s.split("x", 2);
		if (tmp.length < 2) {
			// bad format resort to default
			return def;
		}
		try {
			// pick whatever we got
			return Integer.parseInt(tmp[id]);
		} catch (NumberFormatException e) {
			// bad format (again) resort to default
			return def;
		}
	}

	public static int getHeight() {
		return getHW(PMS.getConfiguration().getWebHeight(), HEIGHT, DEFAULT_HEIGHT);
	}

	public static int getWidth() {
		return getHW(PMS.getConfiguration().getWebWidth(), WIDTH, DEFAULT_WIDTH);
	}

	public static boolean transMp4(String mime, DLNAMediaInfo media) {
		LOGGER.debug("mp4 profile "+media.getH264Profile());
		return mime.equals(MIME_MP4) && (PMS.getConfiguration().isWebMp4Trans() || media.getAvcAsInt() >= 40);
	}

	public static boolean bumpAllowed(String ips, HttpExchange t) {
		IpFilter filter = new IpFilter();
		filter.setRawFilter(ips);
		return filter.allowed(t.getRemoteAddress().getAddress());
	}

	public static String transMime() {
		return MIME_TRANS;
	}
}
