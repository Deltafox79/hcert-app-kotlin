package ehn.techiop.hcert.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import ehn.techiop.hcert.kotlin.chain.Chain
import ehn.techiop.hcert.kotlin.chain.DefaultChain
import ehn.techiop.hcert.kotlin.chain.VerificationResult
import ehn.techiop.hcert.kotlin.chain.impl.PrefilledCertificateRepository
import ehn.techiop.hcert.kotlin.chain.impl.TrustListCertificateRepository
import ehn.techiop.hcert.kotlin.data.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val client by lazy {
        OkHttpClient.Builder()
            .cache(Cache(directory = applicationContext.cacheDir, maxSize = 10L * 1024L * 1024L))
            .build()
    }

    private val trustListFile by lazy { File(applicationContext.filesDir, "trust_list.bin") }

    private val trustSigFile by lazy { File(applicationContext.filesDir, "trust_sig.bin") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<FloatingActionButton>(R.id.fabTrustList).setOnClickListener {
            thread {
                try {
                    downloadTrustListFiles()
                } catch (e: Throwable) {
                    e.printStackTrace()
                    showLog("Error on download: ${e.message}")
                }
            }
        }
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            val intent = IntentIntegrator(this).also {
                it.setOrientationLocked(false)
                it.setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
            }.createScanIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, IntentIntegrator.REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.let { intentResult ->
            intentResult.contents?.let {
                showLog("Validating ...")
                findViewById<LinearLayout>(R.id.container_data).removeAllViews()
                thread {
                    verifyOnBackgroundThread(it)
                }
            }
        }
    }

    private fun verifyOnBackgroundThread(qrCodeContent: String) {
        try {
            val result = getChain().decode(qrCodeContent)
            val data = result.chainDecodeResult.eudgc
            runOnUiThread {
                fillLayout(findViewById(R.id.container_data), data, result.verificationResult)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            runOnUiThread {
                findViewById<LinearLayout>(R.id.container_data).addView(TextView(this).also {
                    it.text = "Error on validation: ${e.message}"
                    it.setTextColor(resources.getColor(R.color.error, theme))
                })
            }
        }
    }

    private fun fillLayout(
        container: LinearLayout,
        data: GreenCertificate?,
        it: VerificationResult
    ) {
        container.removeAllViews()

        if (it.error == null) {
            container.addView(TextView(this).also {
                it.text = "Successfully decoded the contents of the scanned code."
                it.setTextColor(resources.getColor(R.color.success, theme))
            })
        } else {
            container.addView(TextView(this).also {
                it.text = "Decoded the contents of the scanned code with errors."
                it.setTextColor(resources.getColor(R.color.error, theme))
            })
        }
        addTextView(container, "  Error", it.error?.toString())
        addTextView(container, "  Issuer", it.issuer)
        addTextView(container, "  Issued At", it.issuedAt?.toString())
        addTextView(container, "  Expiration", it.expirationTime?.toString())
        addTextView(container, "  Cert. valid from", it.certificateValidFrom?.toString())
        addTextView(container, "  Cert. valid until", it.certificateValidUntil?.toString())
        addTextView(container, "  Cert. valid content", it.certificateValidContent.toString())
        addTextView(container, "  Content", it.content.toString())
        if (data == null) {
            addTextView(container, "No data decoded")
            return
        }
        addTextView(container, "Data decoded:")
        addTextView(container, "  Version", data.schemaVersion)
        addTextView(container, "  DateOfBirth", data.dateOfBirth.toString())
        fillSubject(container, data.subject)
        data.recoveryStatements?.let {
            it.filterNotNull().forEach { rec -> fillRecovery(container, rec) }
        }
        data.tests?.let { it.filterNotNull().forEach { tst -> fillTest(container, tst) } }
        data.vaccinations?.let { it.filterNotNull().forEach { vac -> fillVac(container, vac) } }
    }

    private fun fillSubject(container: LinearLayout, it: Person) {
        addTextView(container, "Person:")
        addTextView(container, "  Given Name", it.givenName)
        addTextView(container, "  Given Name Transliterated", it.givenNameTransliterated)
        addTextView(container, "  Family Name", it.familyName)
        addTextView(container, "  Family Name Transliterated", it.familyNameTransliterated)
    }

    private fun fillRecovery(container: LinearLayout, it: RecoveryStatement) {
        addTextView(container, "Recovery statement:")
        addTextView(container, "  Target", blankSafe(it.target))
        addTextView(
            container,
            "  Date first pos. result",
            it.dateOfFirstPositiveTestResult.toString()
        )
        addTextView(container, "  Cert. valid from", it.certificateValidFrom.toString())
        addTextView(container, "  Cert. valid until", it.certificateValidUntil.toString())
        addTextView(container, "  Country", it.country)
        addTextView(container, "  Cert. Issuer", it.certificateIssuer)
        addTextView(container, "  Cert. Id", it.certificateIdentifier)
    }

    private fun fillTest(container: LinearLayout, it: Test) {
        addTextView(container, "Test:")
        addTextView(container, "  Target", blankSafe(it.target))
        addTextView(container, "  Type", blankSafe(it.type))
        addTextView(container, "  Name (NAA)", it.nameNaa)
        addTextView(container, "  Name (RAT)", it.nameRat?.let { blankSafe(it) })
        addTextView(container, "  Date of sample", it.dateTimeSample.toString())
        addTextView(container, "  Result", blankSafe(it.resultPositive))
        addTextView(container, "  Facility", it.testFacility)
        addTextView(container, "  Country", it.country)
        addTextView(container, "  Cert. Issuer", it.certificateIssuer)
        addTextView(container, "  Cert. Id", it.certificateIdentifier)
    }

    private fun fillVac(container: LinearLayout, it: Vaccination) {
        addTextView(container, "Vaccination:")
        addTextView(container, "  Target", blankSafe(it.target))
        addTextView(container, "  Vaccine", blankSafe(it.vaccine))
        addTextView(container, "  Product", blankSafe(it.medicinalProduct))
        addTextView(container, "  Authorisation Holder", blankSafe(it.authorizationHolder))
        addTextView(container, "  Dose Number", it.doseNumber.toString())
        addTextView(container, "  Total number of doses", it.doseTotalNumber.toString())
        addTextView(container, "  Date", it.date.toString())
        addTextView(container, "  Country", it.country)
        addTextView(container, "  Cert. Issuer", it.certificateIssuer)
        addTextView(container, "  Cert. Id", it.certificateIdentifier)
    }

    private fun blankSafe(adapter: ValueSetEntryAdapter) = when {
        adapter.valueSetEntry.display.isBlank() -> adapter.key
        else -> adapter.valueSetEntry.display
    }

    private fun showLog(content: String) {
        runOnUiThread {
            addTextView(findViewById(R.id.container_data), content)
        }
    }

    private fun addTextView(container: LinearLayout, key: String) {
        container.addView(TextView(this).also {
            it.text = key
        })
    }

    private fun addTextView(container: LinearLayout, key: String, value: String?) {
        value?.let { notnull ->
            container.addView(TextView(this).also {
                it.text = "$key: $notnull"
            })
        }
    }

    private fun getChain(): Chain {
        val trustAnchor = loadTrustListAnchor()
        val trustListContent = try {
            loadWebTrustList()
        } catch (e: Throwable) {
            trustListFile.readBytes()
        }
        val trustListSignature = try {
            loadWebTrustSig()
        } catch (e: Throwable) {
            trustSigFile.readBytes()
        }
        val repository =
            TrustListCertificateRepository(trustListSignature, trustListContent, trustAnchor)
        return DefaultChain.buildVerificationChain(repository)
    }

    private fun downloadTrustListFiles() {
        FileOutputStream(trustListFile, false).use { it.write(loadWebTrustList()) }
        FileOutputStream(trustSigFile, false).use { it.write(loadWebTrustSig()) }
        showLog("Downloaded trust list files")
    }

    private fun loadTrustListAnchor(): PrefilledCertificateRepository {
        val trustAnchorResource = resources.openRawResource(R.raw.trust_list_anchor)
        val trustAnchorCertPem = trustAnchorResource.readBytes().decodeToString()
        return PrefilledCertificateRepository(trustAnchorCertPem)
    }

    private fun loadWebTrustSig() = loadFromWeb("https://dgc.a-sit.at/ehn/cert/sigv2")

    private fun loadWebTrustList() = loadFromWeb("https://dgc.a-sit.at/ehn/cert/listv2")

    private fun loadFromWeb(url: String): ByteArray {
        client.newCall(Request.Builder().get().url(url).build()).execute().use {
            if (!it.isSuccessful || it.body == null)
                throw IllegalArgumentException("Could not load from $url: $it")
            return it.body!!.bytes()
        }
    }

}
