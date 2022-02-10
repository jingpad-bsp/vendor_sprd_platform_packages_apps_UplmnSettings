/**SPRD: UPLMN Feature
 * Uplmn can be read, write and update.
 * Usim fileID is 28512 and sim fileID is 28464.
 *
 **/
package com.sprd.uplmnsettings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.AsyncResult;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.IccConstantsEx;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;

public class UplmnSettings extends Activity {

    private static final String LOG_TAG = "UplmnSettings";
    private static final boolean DBG = true;

    private ArrayAdapter mAdapter;
    private ListView mListView;
    private EditText mET_Index, mET_Plmn, mET_Act;
    private AlertDialog mUplmnEditDialog;

    private String[] mPlmnAct = null;
    private String[] mPlmn = null;
    private String[] mActStr = null;
    private int[] mActInt = null;
    private List<String> mPlmnActList = new ArrayList<String>();
    private List<Integer> mOffset = new ArrayList<Integer>();

    private EventHandler mHandler;
    private Looper mLooper;

    private MyReceiver mReceiver;

    private Phone mPhone;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
    private boolean mIsUsim;

    private IccFileHandler mFh;
    private int mLengthUnit = 0;
    private int mUplmnLen = 0;
    private int mUplmnNum = 0;
    private int mFileID;

    private final static int EF_PLMN_ACT = IccConstantsEx.EF_PLMN_ACT;
    private final static int EF_PLMN_SEL = IccConstantsEx.EF_PLMN_SEL;
    private final static int USIM_LENGTH_UNIT = 5;
    private final static int SIM_LENGTH_UNIT = 3;
    private final static int UPLMN_LEN_LIMIT = 250;
    private final static int UPLMN_LEN_MAX = 100;

    private static final int EVENT_READ_UPLMN_DONE = 100;
    private static final int EVENT_NEW_UPLMN_DONE = 200;
    private static final int EVENT_UPDATE_UPLMN_DONE = 300;
    private static final int EVENT_DELETE_UPLMN_DONE = 400;

    private static final int MENU_NEW_UPLMN =  0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Material_Settings);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.uplmn_list);
        log("onCreate");

        if(!initialize(this.getIntent())){
            finish();
            return;
        }
        readUplmn();

        mListView = (ListView) findViewById(R.id.ListView01);
        mAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, mPlmnActList);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> arg0, View arg1,
                    int position, long id) {
                showEditDialog(position,false);
            }
        });
        mListView.setOnItemLongClickListener(new OnItemLongClickListener(){
            public boolean onItemLongClick(AdapterView<?> parent,View view,int position, long id){
                log("onItemLongClick, position = " + position);
                showConfirmDeleteDialog(position);
                return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
            mReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_NEW_UPLMN, 0,getResources().getString(R.string.menu_new_uplmn))
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case MENU_NEW_UPLMN:
            showEditDialog(mOffset.size(),true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean initialize (Intent intent){
        mLooper = Looper.myLooper();
        mHandler = new EventHandler(mLooper);

        mSubId = intent.getIntExtra("sub_id", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mPhoneId = SubscriptionManager.getPhoneId(mSubId);
        if (!SubscriptionManager.isValidPhoneId(mPhoneId)) {
            log("invalid phoneId");
            return false;
        }

        mIsUsim = isUsimCard(mPhoneId);
        mLengthUnit = mIsUsim ? USIM_LENGTH_UNIT : SIM_LENGTH_UNIT;
        mFileID = mIsUsim? EF_PLMN_ACT : EF_PLMN_SEL;

        mPhone = PhoneFactory.getPhone(mPhoneId);
        mFh = mPhone != null && mPhone.getIccCard() != null ? mPhone.getIccFileHandler() : null;
        if (mFh == null) {
            log("IccFileHandler is null");
            return false;
        }

        mReceiver = new MyReceiver();
        registerReceiver(mReceiver, new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED));

        log("initialize: mSubId = " + mSubId + ", mIsUsim = " + mIsUsim);
        return true;
    }

    /**
     * Read an uplmn from sim
     */
    private void readUplmn() {
        log("readUplmn");
        mFh.loadEFTransparent(mFileID, mHandler.obtainMessage(EVENT_READ_UPLMN_DONE));
    }

    /**
     * Delete an uplmn from sim
     */
    private void deleteUplmn(int index){
        int deleteIndex = index;
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < mUplmnNum; i++) {
            if (i != mOffset.get(deleteIndex)) {
                buffer.append(mPlmnAct[i]);
            }
        }
        for (int i = 0; i < mLengthUnit * 2; i++) {
            buffer.append("F");
        }
        String plmn = buffer.toString();
        mPlmnActList.remove(index);
        mFh.updateEFTransparent(mFileID, IccUtils.hexStringToBytes(plmn), mHandler.obtainMessage(EVENT_DELETE_UPLMN_DONE));
    }

    /**
     * Write a new uplmn into sim
     */
    private void newUplmn(int index){
        String newPlmn= mET_Plmn.getText().toString();
        String newAct = mET_Act.getText().toString();
        String plmnActHex = convertPlmnToHex(newPlmn) + convertActToHex(newAct);
        log("newUplmn : mUplmnNum = " + mUplmnNum + ", index = " + index + ", newPlmn = " + newPlmn + ", newAct = " + newAct);

        if (index < mUplmnNum) {
            StringBuffer buffer = new StringBuffer();
            mOffset.add(index);
            for (int i = 0; i < mUplmnNum; i++) {
                if (i == mOffset.get(index)) {
                    mPlmnAct[i] = plmnActHex;
                    mPlmn[i] = convertPlmnToHex(mPlmnAct[i].substring(0, 6));
                    setAct(i);
                    mPlmnActList.add(newPlmn + ":" + mActStr[i]);
                    if(!mIsUsim) {
                        mPlmnAct[i] = mPlmnAct[i].substring(0, 6);
                    }
                }
                buffer.append(mPlmnAct[i]);
            }
            String finalPlmn = buffer.toString();
            log("newUplmn: " + finalPlmn);
            mFh.updateEFTransparent(mFileID, IccUtils.hexStringToBytes(finalPlmn), mHandler.obtainMessage(EVENT_NEW_UPLMN_DONE));
        } else {
            DisplayToast(getString(R.string.uplmn_exceeds_capacity));
        }
    }

    /**
     * Update uplmn value which has already exist in sim.
     */
    private void updateUplmn(int index){
        String updatePlmn = mET_Plmn.getText().toString();
        String updateAct = mET_Act.getText().toString();
        String plmnActHex = convertPlmnToHex(updatePlmn) + convertActToHex(updateAct);
        log("updateUplmn : mUplmnNum = " + mUplmnNum + ", index = " + index + ", updatePlmn = " + updatePlmn + ", updateAct = " + updateAct);

        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < mUplmnNum; i++) {
            if (i == mOffset.get(index)) {
                mPlmnAct[i] = mIsUsim ? plmnActHex : plmnActHex.substring(0, 6);
            }
            buffer.append(mPlmnAct[i]);
        }
        String finalPlmn = buffer.toString();
        log("updateUplmn: " + finalPlmn);

        setAct(index);
        mPlmnActList.set(index, updatePlmn + ":" + mActStr[index]);
        mFh.updateEFTransparent(mFileID, IccUtils.hexStringToBytes(finalPlmn), mHandler.obtainMessage(EVENT_UPDATE_UPLMN_DONE));
    }

    private AlertDialog getEditDialog(int index, boolean isNew) {
        LayoutInflater factory = LayoutInflater.from(UplmnSettings.this);
        final View view = factory.inflate(R.layout.uplmn_edit, null);
        mET_Index = (EditText) view.findViewById(R.id.index_value);
        mET_Plmn = (EditText) view.findViewById(R.id.id_value);
        mET_Act = (EditText) view.findViewById(R.id.type_value);

        mET_Index.setText(Integer.toString(index));
        mET_Plmn.setText(isNew ? "" : mPlmn[index]);
        mET_Act.setText(isNew ? "" : "" + mActInt[index]);

        mUplmnEditDialog= new AlertDialog.Builder(UplmnSettings.this)
        .setTitle(R.string.uplmn_setting)
        .setView(view)
        .setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog,
                    int which) {
                final String editIndex = mET_Index.getText()
                        .toString();
                final String editPlmn = mET_Plmn.getText()
                        .toString();
                final String editAct = mET_Act.getText()
                        .toString();
                if (!checkInput(editIndex, editPlmn, editAct)) {
                    if (isNew) {
                        newUplmn(index);
                    } else {
                        updateUplmn(index);
                    }
                }
            }
        }).setNegativeButton(android.R.string.cancel, null)
        .create();
        mUplmnEditDialog.setCanceledOnTouchOutside(false);
        return mUplmnEditDialog;
    }

    private void showEditDialog(int index, boolean isNew) {
        if (mUplmnEditDialog != null && mUplmnEditDialog.isShowing()) {
            log("Edit dialog is already showing");
            return;
        }
        AlertDialog dialog = getEditDialog(index, isNew);
        dialog.show();
    }

    private void showConfirmDeleteDialog(final int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(UplmnSettings.this);
        builder.setTitle(R.string.delete)
        .setMessage(R.string.delete_uplmn_confirmation)
        .setCancelable(true)
        .setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog,
                    int which) {
                deleteUplmn(position);
            }
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private boolean checkInput(String editIndex, String editPlmn, String editAct) {
        boolean IsWrong = false;
        Matcher checkAct = Pattern.compile("[^0-9]").matcher(editAct);
        if (checkAct.matches()) {
            IsWrong = true;
            DisplayToast(getString(R.string.type_is_wrong_uplmn));
            return IsWrong;
        }
        Matcher checkPlmn = Pattern.compile("[0-9]+").matcher(editPlmn);
        if (!checkPlmn.matches()) {
            IsWrong = true;
            DisplayToast(getString(R.string.id_is_wrong_uplmn));
            return IsWrong;
        }
        if (editIndex.length() == 0) {
            IsWrong = true;
            DisplayToast(getString(R.string.index_error_uplmn));
            return IsWrong;
        }
        if (editAct.length() == 0) {
            IsWrong = true;
            DisplayToast(getString(R.string.type_is_emplty_error_uplmn));
            return IsWrong;
        }
        if (editPlmn.length() < 5) {
            IsWrong = true;
            DisplayToast(getString(R.string.number_too_short_uplmn));
            return IsWrong;
        }
        if (Integer.parseInt(editAct) > 3 || Integer.parseInt(editAct) < 0) {
            IsWrong = true;
            DisplayToast(getString(R.string.type_is_wrong_uplmn));
            return IsWrong;
        }
        return IsWrong;
    }

    /**
     * Convert the uplmn which user input to hexadecimal data.
     * For example, an input plmn 46000 will be convert to 64F000 first, then 460F00.
     */
    private String convertPlmnToHex(String plmn) {
        String plmnHex = "";
        char[] c = new char[6];
        if (plmn.length() == 5) {
            c[0] = plmn.charAt(1);
            c[1] = plmn.charAt(0);
            c[2] = 'F';
            c[3] = plmn.charAt(2);
            c[4] = plmn.charAt(4);
            c[5] = plmn.charAt(3);
            plmnHex = String.valueOf(c);
        } else if (plmn.length() == 6) {
            c[0] = plmn.charAt(1);
            c[1] = plmn.charAt(0);
            c[2] = plmn.charAt(5);
            c[3] = plmn.charAt(2);
            c[4] = plmn.charAt(4);
            c[5] = plmn.charAt(3);
            plmnHex = String.valueOf(c);
        }
        return plmnHex;
    }

    /**
     * Convert the act(Access Technology Identifier) which user input to hexadecimal data.
     **/
    private String convertActToHex(String s) {
        String actHex = "";
        if (s.equals("0")) { //UTRAN
            actHex = "8000";
        } else if (s.equals("1")) { //GSM
            actHex = "0080";
        } else if (s.equals("2")) { //E-UTRAN
            actHex = "4000";
        } else if (s.equals("3")) { //UTRAN/E-UTRAN/GSM
            actHex = "C0C0";
        }
        return actHex;
    }

    /**
     * Convert act(Access Technology Identifier) to binary data.
     **/
    private String convertActToBinary(String act) {
        int length = act.length();
        for(int i = 0; i < 4 - length; i++ ) {
            act = "0" + act;
        }
        return act;
    }

    private void initializeArray(int len) {
        mPlmnAct = new String[len];
        mPlmn = new String[len];
        mActStr = new String[len];
        mActInt = new int[len];
    }

    /**
     * Set Access Technology Identifier
     * See 3GPP 31.102
     * */
    private void setAct(int index) {
        String utranTag = mPlmnAct[index].substring(6, 7);
        String gsmTag = mPlmnAct[index].substring(8, 9);

        String binaryActUtran = convertActToBinary(Integer.toBinaryString(Integer.parseInt(utranTag, 16)));
        String binaryActGsm = convertActToBinary(Integer.toBinaryString(Integer.parseInt(gsmTag, 16)));

        boolean isUtran = "1".equals(binaryActUtran.substring(0, 1));
        boolean isEutran = "1".equals(binaryActUtran.substring(1, 2));
        boolean isGsm = "1".equals(binaryActGsm.substring(0, 1)) || "1".equals(binaryActGsm.substring(1, 2));

        if(isUtran && isEutran) {
            mActInt[index] = 3;
            mActStr[index] = "U/E/G";
        } else if(isUtran) {
            mActInt[index] = 0;
            mActStr[index] = "U";
        } else if(isEutran) {
            mActInt[index] = 2;
            mActStr[index] = "E";
        } else if(isGsm) {
            mActInt[index] = 1;
            mActStr[index] = "G";
        }
    }

    private class EventHandler extends Handler {
        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            log("handleMessage msg = " + msg);

            AsyncResult ar;
            byte data[];

            switch(msg.what) {
            case EVENT_READ_UPLMN_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null ) {
                    log("Read uplmn exception");
                    DisplayToast(getString(R.string.no_sim_card_prompt));
                    mListView.setVisibility(View.GONE);
                    finish();
                    return;
                }
                data = (byte[])ar.result;
                mUplmnLen = data.length;
                if(mIsUsim && mUplmnLen > UPLMN_LEN_LIMIT) {
                    mUplmnLen = UPLMN_LEN_MAX;
                }
                mUplmnNum = mUplmnLen / mLengthUnit;
                initializeArray(mUplmnNum);

                String allPlmns = IccUtils.bytesToHexString(data);
                log("Plmn total length: " + mUplmnLen + "\n Plmn number: " + mUplmnNum + "\n All plmns: " + allPlmns);

                for (int i = 0; i < mUplmnNum; i++) {
                    mPlmnAct[i] = allPlmns.substring(i * mLengthUnit * 2, (i + 1) * mLengthUnit * 2);
                    if((data[i * mLengthUnit] & 0xff) != 0xff) {
                        mPlmn[i] = IccUtils.bcdPlmnToString(data, i * mLengthUnit);
                        log("mPlmnAct["+ i + "] = "  + mPlmnAct[i]
                                + ", mPlmn[" + i + "] = " + mPlmn[i]);
                        if (mIsUsim) {
                            setAct(i);
                        } else {
                            mActInt[i] = 1;
                            mActStr[i] = "G";
                        }
                        mOffset.add(i);
                        mPlmnActList.add(mPlmn[i] + ":" + mActStr[i]);
                        mAdapter.notifyDataSetChanged();
                        log("mOffset = " + mOffset + ", mPlmnActList=" + mPlmnActList);
                    } else {
                        if(i+1 < mUplmnNum
                                && ((data[i * mLengthUnit] & 0xff) == 0xff)
                                && ((data[(i+1) * mLengthUnit] & 0xff) != 0xff)){
                            //To handle the special case.
                            //When one plmn begins with 'FF'and the next one doesn't begin with 'FF', the previous one still takes up place,
                            //even thouth it's an invalid value.
                            //For example if plmn is'FFFFFFFFFF 516652C0C0 xxxxxxxxxx ...', the index of 'FFFFFFFFFF'is 0.
                            mOffset.add(i);
                            mPlmnActList.add("Invalid Plmn");
                            mAdapter.notifyDataSetChanged();
                        }
                    }
                }
                break;

            case EVENT_NEW_UPLMN_DONE:
            case EVENT_DELETE_UPLMN_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null ) {
                    mAdapter.notifyDataSetChanged();
                    finish();
                    return;
                }
                break;

            case EVENT_UPDATE_UPLMN_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null ) {
                    mAdapter.notifyDataSetChanged();
                    finish();
                    return;
                }
                DisplayToast(getString(R.string.set_uplmn_unsuccessful));
                break;
            default:
                break;
            }
        }
    }

    private void DisplayToast(String str) {
        Toast toast = Toast.makeText(this, str, Toast.LENGTH_SHORT);
        toast.show();
    }

    private void log(String s) {
        if (DBG) {
            Log.d(LOG_TAG, "[UplmnSettings" + mPhoneId + "] " + s);
        }
    }

    private class MyReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                String simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, SubscriptionManager.DEFAULT_PHONE_INDEX);
                log("simState:" + simState + ", phoneId: " + phoneId);
                if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simState)
                        && phoneId == mPhoneId) {
                    log(" finish, if SIM removed");
                    finish();
                }
            }
        }
    };

    private boolean isUsimCard(int phoneId) {
        UiccCardApplication application =
                UiccController.getInstance().getUiccCardApplication(phoneId, UiccController.APP_FAM_3GPP);
        if (application != null) {
            return  application.getType() == AppType.APPTYPE_USIM;
        }
        return false;
    }
}