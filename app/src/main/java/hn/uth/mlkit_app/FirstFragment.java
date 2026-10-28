package hn.uth.mlkit_app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hn.uth.mlkit_app.databinding.FragmentFirstBinding;

public class FirstFragment extends Fragment {

    private static final String STATE_PHOTO_URI = "photo_uri";
    private static final String STATE_RESULT = "result";
    private static final String STATE_IS_URL = "is_url";
    // Lado mayor máximo de la imagen decodificada; suficiente para leer el QR sin agotar memoria.
    private static final int MAX_IMAGE_SIDE = 1600;

    private FragmentFirstBinding binding;
    private BarcodeScanner scanner;
    private ExecutorService executor;

    private Uri photoUri;
    private String result;
    private boolean isUrl;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCamera();
                } else {
                    toast(R.string.camera_permission_denied);
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && photoUri != null) {
                    processPhoto(photoUri, true);
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);
        executor = Executors.newSingleThreadExecutor();

        if (savedInstanceState != null) {
            photoUri = savedInstanceState.getParcelable(STATE_PHOTO_URI, Uri.class);
            result = savedInstanceState.getString(STATE_RESULT);
            isUrl = savedInstanceState.getBoolean(STATE_IS_URL);
        }
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {

        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonScan.setOnClickListener(v -> onScanClicked());
        binding.buttonOpen.setOnClickListener(v -> openUrl(result));
        binding.buttonCopy.setOnClickListener(v -> copyResult());

        showResult();
        if (photoUri != null && result != null) {
            // Restaurar la foto tras rotación sin volver a escanear ni reabrir el navegador.
            processPhoto(photoUri, false);
        }
    }

    private void onScanClicked() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        try {
            File dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File photo = File.createTempFile("QR_" + name + "_", ".jpg", dir);
            photoUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", photo);
            takePictureLauncher.launch(photoUri);
        } catch (IOException e) {
            toast(R.string.photo_file_error);
        } catch (ActivityNotFoundException e) {
            toast(R.string.no_camera_app);
        }
    }

    /**
     * Decodifica la foto (respetando la orientación EXIF) y, si {@code scan} es verdadero,
     * la analiza con ML Kit en busca de un código QR.
     */
    private void processPhoto(Uri uri, boolean scan) {
        setLoading(scan);
        Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            Bitmap bitmap;
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    decoder.setTargetSampleSize(Math.max(1, largest / MAX_IMAGE_SIDE));
                });
            } catch (IOException e) {
                bitmap = null;
            }
            Bitmap decoded = bitmap;
            runOnUi(() -> {
                if (decoded == null) {
                    setLoading(false);
                    toast(R.string.photo_load_error);
                    return;
                }
                showPhoto(decoded);
                if (scan) {
                    scan(decoded);
                }
            });
        });
    }

    private void scan(Bitmap bitmap) {
        scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(this::onBarcodes)
                .addOnFailureListener(e -> {
                    setLoading(false);
                    toast(R.string.scan_error);
                });
    }

    private void onBarcodes(List<Barcode> barcodes) {
        if (binding == null) {
            return;
        }
        setLoading(false);
        if (barcodes.isEmpty() || barcodes.get(0).getRawValue() == null) {
            result = null;
            isUrl = false;
            showResult();
            binding.textResult.setText(R.string.no_qr_found);
            return;
        }

        Barcode barcode = barcodes.get(0);
        if (barcode.getValueType() == Barcode.TYPE_URL && barcode.getUrl() != null
                && barcode.getUrl().getUrl() != null) {
            result = barcode.getUrl().getUrl();
            isUrl = true;
        } else {
            result = barcode.getRawValue();
            isUrl = looksLikeUrl(result);
        }
        showResult();

        if (isUrl) {
            openUrl(result);
        }
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void openUrl(String url) {
        if (url == null) {
            return;
        }
        Uri uri = Uri.parse(url.trim());
        if (uri.getScheme() == null) {
            uri = Uri.parse("https://" + url.trim());
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast(R.string.no_browser);
        }
    }

    private void copyResult() {
        if (result == null) {
            return;
        }
        ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.result_label), result));
    }

    private void showPhoto(Bitmap bitmap) {
        binding.imageQr.setImageBitmap(bitmap);
        binding.imageQr.setVisibility(View.VISIBLE);
        binding.layoutPlaceholder.setVisibility(View.GONE);
    }

    private void showResult() {
        boolean hasResult = result != null;
        binding.textResult.setText(hasResult ? result : getString(R.string.result_empty));
        binding.textResult.setAlpha(hasResult ? 1f : 0.6f);
        binding.chipType.setVisibility(hasResult ? View.VISIBLE : View.GONE);
        binding.chipType.setText(isUrl ? R.string.type_url : R.string.type_text);
        binding.chipType.setChipIconResource(isUrl ? R.drawable.ic_link : R.drawable.ic_text);
        binding.buttonOpen.setVisibility(isUrl ? View.VISIBLE : View.GONE);
        binding.buttonCopy.setEnabled(hasResult);
    }

    private void setLoading(boolean loading) {
        if (binding == null) {
            return;
        }
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonScan.setEnabled(!loading);
    }

    private void runOnUi(Runnable action) {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(() -> {
            if (binding != null) {
                action.run();
            }
        });
    }

    private void toast(int message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_PHOTO_URI, photoUri);
        outState.putString(STATE_RESULT, result);
        outState.putBoolean(STATE_IS_URL, isUrl);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scanner.close();
        executor.shutdown();
    }

}
