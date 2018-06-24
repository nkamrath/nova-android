package com.example.nate.testesp32bluetooth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Created by Nate on 12/28/2017.
 */

public class BluetoothCommand {
    private int mType;
    private int mLength;
    private String mSecretKey = "$AeA2d3vd2dej&*9l1AkIyvB0@#4^H!r";
    private String mPassword;
    private long mAuthCounter = 0;
    byte[] mData;

    BluetoothCommand(int type, byte data[], String password, long authCounter){
        mType = type;
        mLength = data.length;
        mPassword = password;
        mAuthCounter = authCounter;
        mData = data;
    }

    public byte[] getBytes(){
        byte[] data = new byte[20];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            digest.update(mData);
            digest.update((byte)(mAuthCounter));
            digest.update((byte)(mAuthCounter>>8));
            digest.update((byte)(mAuthCounter>>16));
            digest.update((byte)(mAuthCounter>>24));
            digest.update((byte)(mAuthCounter>>32));
            digest.update((byte)(mAuthCounter>>40));
            digest.update((byte)(mAuthCounter>>48));
            digest.update((byte)(mAuthCounter>>56));
            digest.update(mSecretKey.getBytes());
            digest.update(mPassword.getBytes());

            byte hash[] = digest.digest();

            data[0] = (byte) mType;
            data[1] = (byte) mLength;
            data[2] = hash[0];
            data[3] = hash[1];
            data[4] = hash[2];
            data[5] = hash[3];

            System.arraycopy(mData, 0, data, 6, mData.length);

            return data;

        } catch (NoSuchAlgorithmException e1){
            return null;
        }

    }
}
