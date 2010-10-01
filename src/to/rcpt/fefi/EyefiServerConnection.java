package to.rcpt.fefi;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CheckedInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;

import to.rcpt.fefi.eyefi.EyefiIntegrityDigest;
import to.rcpt.fefi.eyefi.EyefiMessage;
import to.rcpt.fefi.eyefi.GetPhotoStatus;
import to.rcpt.fefi.eyefi.GetPhotoStatusResponse;
import to.rcpt.fefi.eyefi.StartSession;
import to.rcpt.fefi.eyefi.StartSessionResponse;
import to.rcpt.fefi.eyefi.UploadPhoto;
import to.rcpt.fefi.eyefi.UploadPhotoResponse;
import to.rcpt.fefi.eyefi.Types.MacAddress;
import to.rcpt.fefi.eyefi.Types.ServerNonce;
import to.rcpt.fefi.eyefi.Types.UploadKey;
import to.rcpt.fefi.util.Hexstring;
import to.rcpt.fefi.util.MultipartInputStream;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Images.Media;
import android.util.Log;

public class EyefiServerConnection extends DefaultHttpServerConnection implements Runnable {
	public static final String TAG = "EyefiServerConnection";
//	private UploadKey uploadKey = null;
	private static final ServerNonce serverNonce = new ServerNonce("deadbeefdeadbeefdeadbeefdeadbeef");
	private EyefiReceiverService context;
	private static final String CONTENT_DISPOSITION_PREAMBLE = "form-data; name=\"";
	private static final String URN_GETPHOTOSTATUS = "\"urn:GetPhotoStatus\"";
	private static final String URN_STARTSESSION = "\"urn:StartSession\"";
	
	public static EyefiServerConnection makeConnection(EyefiReceiverService c, Socket s) throws IOException {
		s.setReceiveBufferSize(256 * 1024);
		Log.d(TAG, "recv buffer size " + s.getReceiveBufferSize());
		EyefiServerConnection me = new EyefiServerConnection(c);
		BasicHttpParams params = new BasicHttpParams();
		params.setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 256 * 1024);
		me.bind(s, params);
		return me;
	}
	
	protected EyefiServerConnection(EyefiReceiverService c) {
		context = c;
//		uploadKey = new UploadKey("a8378747b56aa0c49d608bec38b159e8");
//		String key = "a8378747b56aa0c49d608bec38b159e8";
//		int uploadKeyLength = key.length() / 2;
//		uploadKey = new byte[uploadKeyLength];
//		for(int i = 0; i < uploadKeyLength; ++i) {
//			String hexpair = key.substring(2 * i, 2 * i + 2);
//			uploadKey[i] = (byte)(Integer.parseInt(hexpair, 16) & 0xff);
//		}
	}
	
	public void getPhotoStatus(HttpRequest request)
			throws HttpException, IOException {
		Log.d(TAG, "getPhotoStatus");
		if (!(request instanceof HttpEntityEnclosingRequest))
			throw new RuntimeException(); // TODO: something useful
		HttpEntityEnclosingRequest eyefiRequest = (HttpEntityEnclosingRequest) request;
		receiveRequestEntity(eyefiRequest);
		GetPhotoStatus photoStatus = new GetPhotoStatus();
		photoStatus.parse(eyefiRequest.getEntity().getContent());
		MacAddress mac = photoStatus.getMacAddress();
		UploadKey uploadKey = getKeyForMac(mac);
		if(uploadKey != null)
			photoStatus.authenticate(uploadKey, serverNonce);
		else {
			uploadKey = context.registerUnknownMac(mac);
			if(uploadKey != null) {
				photoStatus.authenticate(uploadKey, serverNonce);
				DBAdapter db = DBAdapter.make(context);
				try {
					long id = db.addNewKeyWithMac(mac, uploadKey);
					Log.d(TAG, "registered " + mac + " with id " + id);
					context.registerNewCard(mac, uploadKey, id);
				} finally {
					db.close();
				}
			} else {
				close();
				return;
			}
		}
		int offset = 0;
		String filesignature = photoStatus.getParameter("filesignature");
		DBAdapter db = DBAdapter.make(context);
		if(db.imageExists(filesignature)) {
			Log.i(TAG, "image " + filesignature + "exists");
			offset = -1;
		} else
			Log.i(TAG, "new image " + filesignature);
		db.close();
//		Cursor results = getCursor(photoStatus.getParameter("filesignature"));
//		boolean imageExists = results.moveToFirst();
//		if(imageExists)
//			offset = results.getInt(results.getColumnIndex(MediaColumns.SIZE));
//		results.close();
//		Log.d(TAG, "existence: " + imageExists + "; offset " + offset);
		// TODO: how to reject an image? offset presumably resumes upload;
		// what's the point of fileid? oh - returned by client in upload
		GetPhotoStatusResponse gpsr = new GetPhotoStatusResponse(photoStatus,
				1, offset);
		sendResponseHeader(gpsr);
		sendResponseEntity(gpsr);
	}


	private Cursor getCursor(String signature) {
		String imageName = "eyefi/" + signature;
		Log.d(TAG, "searching for " + imageName);
		ContentResolver cr = context.getContentResolver();
		String[] columns = { BaseColumns._ID, MediaColumns.SIZE };
		String filter = Images.Media.DISPLAY_NAME + " = ? ";
		String placeholders[] = { imageName };
		Cursor results = cr.query(Images.Media.EXTERNAL_CONTENT_URI, columns,
				filter, placeholders, null);
		return results;
	}

	public void run() {
		try {
			while (isOpen()) {
				Log.d(TAG, "waiting for request");
				HttpRequest request = receiveRequestHeader();
				String uri = request.getRequestLine().getUri();
				if (uri.equals("/api/soap/eyefilm/v1")) {
					Header[] soapActions = request.getHeaders("SOAPAction");
					if ((soapActions == null) || (soapActions.length == 0)) {
						Log.e(TAG, "no SOAPAction");
						close();
					} else {
						String action = soapActions[0].getValue();
						if (action.equals(URN_STARTSESSION))
							startSession(request);
						else if (action.equals(URN_GETPHOTOSTATUS))
							getPhotoStatus(request);
						else {
							Log.e(TAG, "unknown SOAPAction: " + action);
							close();
						}
					}
				} else if (uri.equals("/api/soap/eyefilm/v1/upload")) {
					uploadPhoto(request);
				} else {
					Log.e(TAG, "unknown method " + uri);
					close();
				}
			}
		} catch (Exception e) {
			// TODO: handle exception
		}
		
	}

	public UploadKey getKeyForMac(MacAddress mac) {
		DBAdapter db = DBAdapter.make(context);
		UploadKey uploadKey;
		try {
			uploadKey = db.getUploadKeyForMac(mac);
		} finally {
			db.close();
		}
		return uploadKey;
	}
	
	public void startSession(HttpRequest request)
			throws HttpException, IOException {
		if (!(request instanceof HttpEntityEnclosingRequest))
			throw new RuntimeException(); // TODO: something useful
		HttpEntityEnclosingRequest eyefiRequest = (HttpEntityEnclosingRequest) request;
		receiveRequestEntity(eyefiRequest);
		StartSession ss = StartSession.parse(eyefiRequest.getEntity()
				.getContent());
		Log.d(TAG, "parsed startsession");
		MacAddress mac = ss.getMacaddress();
		UploadKey uploadKey = getKeyForMac(mac);
		if(uploadKey == null)
			uploadKey = context.registerUnknownMac(mac);
		if(uploadKey == null) {
			close();
			return;
		}
		StartSessionResponse ssr = new StartSessionResponse(ss, uploadKey,
				serverNonce);
		sendResponseHeader(ssr);
		sendResponseEntity(ssr);
	}

	public void uploadPhoto(HttpRequest request) 
			throws HttpException, IOException {
		Log.d(TAG, "upload " + request.toString());
		HttpEntityEnclosingRequest eyefiRequest = (HttpEntityEnclosingRequest) request;
		Header contentTypes[] = eyefiRequest.getHeaders("Content-type");
		if(( contentTypes == null) || contentTypes.length < 1) {
			Log.e(TAG, "no content type in upload request");
			close();
		}
		HeaderElement elements[] = contentTypes[0].getElements();
		if((elements == null) || elements.length < 1) {
			Log.e(TAG, "bad content type in upload request");
			close();
		}
		if(!elements[0].getName().equals("multipart/form-data")) {
			Log.e(TAG, "content-type not multipart/form-data in upload");
			close();
		}
		// for some reason, we don't get an element with the boundary. find it manually.
		String b = "boundary=";
		String value = contentTypes[0].getValue();
		int pos = value.indexOf(b);
		if(pos < 0) {
			Log.e(TAG, "no boundary in content-type");
			close();
		}
		String boundary = "--" + value.substring(pos + b.length()) + "\r\n";
		Log.d(TAG, "identified boundary " + boundary);
		// okay, ready to start reading data
		receiveRequestEntity(eyefiRequest);
		HttpEntity entity = eyefiRequest.getEntity();
		Log.d(TAG, "entity " + entity + " len " + entity.getContentLength());
		MultipartInputStream in = new MultipartInputStream(
				new BufferedInputStream(entity.getContent(), 400000), boundary);
		Log.d(TAG, "made in " + in);
		// find first boundary; should be at start
		in.close();
		Map<String, String> headers = getHeaders(in, boundary);
		UploadPhoto uploadPhoto = null;
		Uri uri = null;
		String log = null;
		String fileSignature = null;
		while(!headers.isEmpty()) {
			String contentDisposition = headers.get("Content-Disposition");
			if(contentDisposition == null)
				break;
			if(!contentDisposition.startsWith(CONTENT_DISPOSITION_PREAMBLE))
				break;
			int endPos = contentDisposition.indexOf('"', CONTENT_DISPOSITION_PREAMBLE.length());
			if(endPos < 0)
				break;
			String partName = contentDisposition.substring(CONTENT_DISPOSITION_PREAMBLE.length(), endPos);
			if(partName.equals("SOAPENVELOPE")) {
				UploadPhoto u = new UploadPhoto();
				u.parse(in);
				uploadPhoto = u;
			} else if(partName.equals("FILENAME")) {
				DBAdapter db = DBAdapter.make(context);
				long index = db.addImage(uploadPhoto.getParameter(EyefiMessage.FILESIGNATURE) + "-tmptar", Media.EXTERNAL_CONTENT_URI, "foo");
				db.close();
//				File path = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
//				File destination = new File(path, index + ".tar");
//				Log.d(TAG, "shuffling image to " + destination);
//				FileOutputStream fos = new FileOutputStream(destination);
//				// argh. 
//				int bufsize = 256 * 1024 + 1;
//				byte buf[] = new byte[bufsize];
//				int read = in.read(buf);
//				while(read != -1) {
//					long t1 = System.currentTimeMillis();
//					fos.write(buf, 0, read);
//					long t2 = System.currentTimeMillis();
//					long delta = t2 - t1;
//					if(delta > 0)
//						Log.d(TAG, "wrote " + read + " bytes in " + delta + " ms for W" + read/delta);
//					else
//						Log.d(TAG, "wrote " + read + " bytes in " + delta + " ms");
//					read = in.read(buf);
//					t1 = System.currentTimeMillis();
//					delta = t1 - t2;
//					if(delta > 0)
//						Log.d(TAG, "read  " + read + " bytes in " + delta + " ms for R" + read/delta);
//					else
//						Log.d(TAG, "read  " + read + " bytes in " + delta + " ms");
//				}
//				fos.close();
//				Log.d(TAG, "shuffled image to " + destination);
				EyefiIntegrityDigest checksum = new EyefiIntegrityDigest();
				TarInputStream tarball = new TarInputStream(new CheckedInputStream(in, checksum));
				TarEntry file = tarball.getNextEntry();
				while(file != null) {
					String fileName = file.getName();
					if(fileName.endsWith(".log")) {
						Log.d(TAG, "Found logfile " + fileName);
						ByteArrayOutputStream out = new ByteArrayOutputStream();
						tarball.copyEntryContents(out);
						log = out.toString();
					} else {
						Log.d(TAG, "Processing image file " + fileName);
						if(uploadPhoto == null)
							break;
						db = DBAdapter.make(context);
						fileSignature = uploadPhoto.getParameter(EyefiMessage.FILESIGNATURE);
						boolean fileExists = db.imageExists(fileSignature);
						db.close();
						if(!fileExists) {
//							db = DBAdapter.make(context);
//							long index = db.addImage(uploadPhoto.getParameter(EyefiMessage.FILESIGNATURE) + "-tmp", Media.EXTERNAL_CONTENT_URI, "foo");
//							db.close();
//							File path = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
//							File destination = new File(path, index + ".jpg");
//							Log.d(TAG, "shuffling image to " + destination);
//							tarball.copyEntryContents(new FileOutputStream(destination));
//							Log.d(TAG, "shuffled image to " + destination);
							ContentValues values = new ContentValues();
							values.put(Media.DISPLAY_NAME, "eyefi/" + fileSignature);
							values.put(Media.BUCKET_DISPLAY_NAME, "bucket display?");
							values.put(Media.DESCRIPTION, "description");
							values.put(Media.TITLE, "title");
							values.put(Media.MIME_TYPE, "image/jpeg");
							values.put(MediaColumns.SIZE, (int)file.getSize());
							ContentResolver cr = context.getContentResolver();
							uri = cr.insert(Media.EXTERNAL_CONTENT_URI, values);
							try {
								OutputStream out = cr.openOutputStream(uri);
								Log.d(TAG, "shuffling image to " + uri);
								long t1 = System.currentTimeMillis();
								tarball.copyEntryContents(out);
								out.close();
								long t2 = System.currentTimeMillis();
								long delta = t2 - t1;
								Log.d(TAG, "done with " + uri + " copy after " + delta + " ms ");
							} catch(IOException e) {
								Log.e(TAG, "IO fail " + e);
							}
						} else
							Log.e(TAG, "file exists!");
					}
					Log.d(TAG, "skipping to next entry");
					file = tarball.getNextEntry();
				}
				UploadKey uploadKey = getKeyForMac(uploadPhoto.getMacAddress());
				Hexstring integrityDigest = checksum.getValue(uploadKey);
				Log.d(TAG, "calculated digest " + integrityDigest);
			}
			in.close();
			headers = getHeaders(in, boundary);
		}
		Log.d(TAG, " done with multipart input, have a uri " + uri);
		if(uri != null) {
			// we saved something; we should track it
			DBAdapter db = DBAdapter.make(context);
			db.addImage(fileSignature, uri, log);
			db.close();
		}
		UploadPhotoResponse response = new UploadPhotoResponse(true);
		sendResponseHeader(response);
		sendResponseEntity(response);
	}

	public Map<String, String> getHeaders(MultipartInputStream in, String boundary) 
			throws IOException {
		Map<String, String> headers = new HashMap<String, String>();
		in.setBoundary("\r\n\r\n");
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		String line = reader.readLine();
		while(line != null) {
			int pos = line.indexOf(": ");
			if(pos > 0)
				headers.put(line.substring(0, pos), line.substring(pos + 2));
			line = reader.readLine();
		}
		in.setBoundary("\r\n" + boundary);
		return headers;
	}

}