package to.rcpt.fefi.eyefi;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Vector;
import java.util.zip.Checksum;

import android.util.Log;

public class EyefiIntegrityDigest implements Checksum {
	private int counterValue = 0;
	private int bytesCounted = 0;
	private int spare = -1;
	private Vector<Integer> blockChecksums = new Vector<Integer>(2000, 2000);
	public static final String TAG = "EyefiIntegrityDigest"; 

	public byte[] getValue(byte[] uploadKey) {
		Log.d(TAG, "getValue " + bytesCounted);
		if(bytesCounted > 0)
			completeBlock();
		int numBlocks = blockChecksums.size();
		byte plaintext[] = new byte[numBlocks * 2 + uploadKey.length];
		for(int i = 0; i < numBlocks; i++) {
			plaintext[2 * i] =  (byte)(blockChecksums.get(i) >> 8);
			plaintext[2 * i + 1] = (byte)(blockChecksums.get(i) & 0xff);
		}
		Log.d(TAG, "total plaintext length " + plaintext.length);
		System.arraycopy(uploadKey, 0, plaintext, numBlocks * 2, uploadKey.length);
		byte start[] = new byte[40];
		int i = 0;
		for(; i < plaintext.length - 39; i += 40) {
			System.arraycopy(plaintext, i, start, 0, 40);
			Log.d(TAG, "plaintext@" + i + ": " + EyefiMessage.toHexString(start));
		}
		if(i != plaintext.length) {
			start = new byte[plaintext.length - i];
			System.arraycopy(plaintext, i, start, 0, plaintext.length - i);
			Log.d(TAG, "plaintext@" + i + ": " + EyefiMessage.toHexString(start));			
		}
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] authentication = md.digest(plaintext);
			Log.d(TAG, "calculated " + EyefiMessage.toHexString(authentication));
			return authentication;
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
	
	public long getValue() {
		return 0;
	}

	public void reset() {
		counterValue = 0;
		bytesCounted = 0;
	}

	public void updateWord(int val) {
		counterValue += val & 0xffff;
		bytesCounted += 2;
		if(bytesCounted == 512) {
			completeBlock();
		}
	}

	private void completeBlock() {
		int sum16 = counterValue;
		while((sum16 & ~0xffff) != 0)
			sum16 = (sum16 & 0xffff) + (sum16 >> 16);
//		int sum16 = ~((counterValue & 0xffff) + ((counterValue >> 16) & 0xffff)) & 0xffff;
//		Log.d(TAG, "block checksum " + Integer.toHexString(sum16));
		blockChecksums.add(~sum16);
		counterValue = 0;
		bytesCounted = 0;
	}
	
	public void update(int val) {
		if(spare < 0)
			spare = val & 0xff;
		else {
			int word = (spare << 8) + (val & 0xff);
			updateWord(word);
			spare = -1;
		}
	}

	public void update(byte[] buf, int off, int nbytes) {
		int i;
		if(spare >= 0) {
			update(buf[off]);
			off++;
			nbytes--;
		}
		for(i = off; i < off + nbytes - 1; i += 2) {
			updateWord(((((int)buf[i]) << 8) & 0xff00) | (((int)buf[i + 1]) & 0xff));
		}
		if(i != off + nbytes)
			update(buf[i]);
	}
}
