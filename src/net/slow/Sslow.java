package net.slow;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet implementation class Sslow
 */
@WebServlet("/slow")
public class Sslow extends HttpServlet {
	private static final long serialVersionUID = 1L;
	int speed = 1024; // bytes per second. configed
	int stopAt = 1024 * 30; // configed
	private File fDir;
	private String dir = "./files"; // configed "." is the tomcat root, so "./files" is parallel to tomcat bin

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public Sslow() {
		super();
		// TODO Auto-generated constructor stub
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// TODO Auto-generated method stub
		// response.getWriter().append("Served at: ").append(request.getContextPath());
		String q = request.getQueryString();
		System.out.println(q);
		String url = request.getParameter("url");
		System.out.println("url:" + url);
		URL ourl = new URL(url);
		System.out.println("file:" + ourl.getFile());
		String q0 = ourl.getQuery();
		System.out.println("q0:" + q0);
		String tag = "md5=";
		int istart = q0.indexOf(tag);
		String md5 = null;
		if (istart != -1) {
			istart += tag.length();
			int iend = q0.indexOf('=', istart);
			if (iend != -1) {
				md5 = q0.substring(istart, iend);
			} else
				md5 = q0.substring(istart);
			System.out.println("md5:" + md5);
		}
		dumpHeaders(request);
		String range = request.getHeader("Range");
		int posStart = 0;
		if (range != null) {
			tag = "bytes=";
			istart = range.indexOf(tag);
			if (istart != -1) {
				istart += tag.length();
				int iend = range.indexOf('-', istart);
				if (iend != -1) {
					posStart = Integer.parseInt(range.substring(istart, iend));
				}
			}
		}
		if (md5 != null) {
			FileResource fr = files.get(md5);
			if (fr == null) {
				fr = new FileResource();
				files.put(md5, fr);
			}
			fr.md5 = md5;
			fr.ourl = ourl;
			fr.requested++;
			Task task = new Task(fr, response, posStart);
			task.serve();
		}
	}

	static HashMap<String, FileResource> files = new HashMap<>();

	class FileResource {
		String md5;
		File file;
//		String pathLocal;
		URL ourl;
		int requested=-1; // first time: give 10 bytes, 2nd time: timeout at stopAt, 3rd time: good.

		private File getFile() throws IOException {
			File dir = getDir();
			file = new File(dir, md5);
			if (file.exists()) {
			} else {
				if (dir.exists()) {
				} else
					dir.mkdirs();
				download();
			}
			return file;
		}

		private void download() throws IOException {
			HttpURLConnection connection = (HttpURLConnection) ourl.openConnection();
			// just want to do an HTTP GET here
			connection.setRequestMethod("GET");
			int timeout = 10 * 1000;
			connection.setReadTimeout(timeout);
			connection.connect();
			int code = connection.getResponseCode();
			if (code != 200 && code != 206) {
				StringBuilder sb = new StringBuilder();
				sb.append("{\"code\":").append(code).append(",\"headers\":[");
				try {
					for (int i = 0; i < 20; i++) {
						String n = connection.getHeaderFieldKey(i), v = connection.getHeaderField(i);
						if (v != null) {
							if (i > 0)
								sb.append(',');
							sb.append("{\"name\":\"" + n + "\",\"value\":\"" + v + "\"}");
						}
					}
				} catch (Throwable t) {
					t.printStackTrace();
				}
				sb.append("]}");
				String msg = sb.toString();
				throw new IllegalStateException(msg);
			}
//			if (this.mimeType == null) {
//				this.mimeType = connection.getContentType();
//				this.encoding = connection.getContentEncoding();
//			}
//			if (this.size == 0) {
//				this.size = connection.getContentLength();
//				if (this.fdownloading != null) {
//					this.size += this.fdownloading.length();
//				}
//			}
			// read the output from the server
			BufferedInputStream bis = new BufferedInputStream(connection.getInputStream());
//            if (this.f == null) {
//                //this.f = new File(gameFiles, System.currentTimeMillis() + ".tmp");
//                this.f = new File(gameFiles, filename.incrementAndGet() + ".tmp");
//            }
			FileOutputStream fos = new FileOutputStream(this.file); // , false
			// Java 9, InputStream provides a method called transferTo
			// Piped stream can be used when we have two threads.
			// PipedOutputStream pos=new PipedOutputStream();
			byte[] buf = new byte[4096];
			int len = -1;
			while (true) {
				try {
					len = bis.read(buf);
				} catch (java.net.ProtocolException t) {
					throw t;
				}
				if (len <= 0)
					break;
				fos.write(buf, 0, len);
			}
			fos.flush();
			fos.close();
			bis.close();
			System.out.println("downloaded");
		}

	}

	class Task {
		FileResource fr;
		HttpServletResponse response;
		int posStart, sentTotal;

		public Task(FileResource fr, HttpServletResponse response, int posStart) {
			this.fr = fr;
			this.response = response;
			this.posStart = posStart;
			sentTotal = posStart;
		}

		byte[] bytes = new byte[4096];
		int len;
		ServletOutputStream sos;

		public void serve() {
			if (fr.requested == 0) {
				serve_short();
			} else if (fr.requested == 1)
				serve_timeout(); // should be in another thread if async
			else {
//				if (fr.requested == 2)
				serve_good();
//				else {
//					System.out.println("unknown request");
//				}
			}
		}

		private void serve_short() {
			try {
				File f = fr.getFile();
				BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
				long flen = f.length();
				if (flen >= 10)
					flen = 10;
				else
					flen = flen / 2;
				if (posStart != 0) {
					response.setHeader("Content-Range", "bytes " + posStart + "-" + (flen - 1) + "/" + flen);
					bis.skip(posStart);
					response.setStatus(206);
				} else {
					response.setContentLengthLong(flen);
					response.setStatus(200);
				}
				sos = response.getOutputStream();
				int bytesSent=0;
				while (true) {
					len = bis.read(bytes);
					if (len <= 0)
						break;
					if(bytesSent+len>flen) {
						len = (int) (flen - bytesSent);
					}
					sos.write(bytes, 0, len);
					bytesSent+=len;
					break;
				}
				sos.close();
			} catch (Throwable e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		private void serve_good() {
			try {
				File f = fr.getFile();
				BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
				long flen = f.length();
				if (posStart != 0) {
					response.setHeader("Content-Range", "bytes " + posStart + "-" + (flen - 1) + "/" + flen);
					bis.skip(posStart);
					response.setStatus(206);
				} else {
					response.setContentLengthLong(flen);
					response.setStatus(200);
				}
				sos = response.getOutputStream();
				while (true) {
					len = bis.read(bytes);
					if (len <= 0)
						break;
					sos.write(bytes, 0, len);
				}
				sos.close();
			} catch (Throwable e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		public void serve_timeout() { // slow and timeout at stopAt
			try {
				File f = fr.getFile();
				BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
				long flen = f.length();
				if (posStart != 0) {
					response.setHeader("Content-Range", "bytes " + posStart + "-" + (flen - 1) + "/" + flen);
					bis.skip(posStart);
					response.setStatus(206);
				} else {
					response.setContentLengthLong(flen);
					response.setStatus(200);
				}
				sos = response.getOutputStream();
				while (true) {
					len = bis.read(bytes);
					if (len <= 0)
						break;
					if (deliverSlowAndTimeout())
						continue;
					break;
				}
				sos.close();
			} catch (Throwable e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		int second_sent;
		long second_start;

		private boolean deliverSlowAndTimeout() throws InterruptedException, IOException {
			int offset = 0;
			while (true) { // generally 1 loop per second unless speed is high, or lack of data
				int bytes2send = speed - second_sent;
				if (bytes2send > 0) {
					if (offset + bytes2send <= len) {
					} else {
						bytes2send = len - offset;
					}
					sos.write(bytes, offset, bytes2send);
					offset += bytes2send;
					second_sent += bytes2send;
					sentTotal += bytes2send;
					sos.flush();
				}
				// second finished
				if (stopAt <= sentTotal && sentTotal < stopAt + speed) {
					Thread.sleep(1024 * 60 * 60 * 24); // sleep for 1 day.
//					return false; is not good. the client will treat it as a normal close.
				}
				if (offset < len) { // we still have data. sleep or not sleep.
					long dt = second_start + 1000 - System.currentTimeMillis();
					if (dt > 0)
						Thread.sleep(dt);
					// now new second starts
					second_start = System.currentTimeMillis(); // for 1st second, this is 0
					second_sent = 0;
				} else
					break; // no more data
			}
			return true;// should continue;
		}

	}

	private void dumpHeaders(HttpServletRequest request) {

		Enumeration<String> e = request.getHeaderNames();
		while (e.hasMoreElements()) {
			String name = e.nextElement();
			Enumeration<String> e2 = request.getHeaders(name);
			while (e2.hasMoreElements()) {
				String value = e2.nextElement();
				System.out.println(name + ":" + value);
			}
		}

	}

	public File getDir() {
		if (fDir == null) {
			fDir = new File(dir);
			System.out.println(dir + ":" + fDir.getAbsolutePath());
		}
		return fDir;
	}

}
