/**
 *
 */
package de.metas.payment.api.impl;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.invoice.service.IInvoiceDAO;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.model.PlainContextAware;
import org.adempiere.service.ClientId;
import org.adempiere.service.ISysConfigBL;
import org.adempiere.util.lang.Mutable;
import org.compiere.model.I_C_AllocationHdr;
import org.compiere.model.I_C_AllocationLine;
import org.compiere.model.I_C_DocType;
import org.compiere.model.I_C_Invoice;
import org.compiere.model.I_C_Payment;
import org.compiere.util.TimeUtil;
import org.compiere.util.TrxRunnableAdapter;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableSet;

import de.metas.allocation.api.IAllocationBL;
import de.metas.currency.CurrencyConversionContext;
import de.metas.currency.CurrencyPrecision;
import de.metas.currency.ICurrencyBL;
import de.metas.currency.ICurrencyDAO;
import de.metas.currency.exceptions.NoCurrencyRateFoundException;
import de.metas.document.DocTypeId;
import de.metas.document.IDocTypeDAO;
import de.metas.document.engine.DocStatus;
import de.metas.invoice.InvoiceId;
import de.metas.logging.LogManager;
import de.metas.money.CurrencyConversionTypeId;
import de.metas.money.CurrencyId;
import de.metas.organization.OrgId;
import de.metas.payment.PaymentId;
import de.metas.payment.TenderType;
import de.metas.payment.api.DefaultPaymentBuilder;
import de.metas.payment.api.IPaymentBL;
import de.metas.payment.api.IPaymentDAO;
import de.metas.payment.api.PaymentQuery;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;

public class PaymentBL implements IPaymentBL
{
	private static final Logger logger = LogManager.getLogger(PaymentBL.class);
	private final IPaymentDAO paymentDAO = Services.get(IPaymentDAO.class);
	private final IAllocationBL allocationBL = Services.get(IAllocationBL.class);
	private final IInvoiceDAO invoiceDAO = Services.get(IInvoiceDAO.class);
	private final ICurrencyBL currencyBL = Services.get(ICurrencyBL.class);
	private final ICurrencyDAO currencyDAO = Services.get(ICurrencyDAO.class);
	private final ISysConfigBL sysConfigBL = Services.get(ISysConfigBL.class);
	private final ITrxManager trxManager = Services.get(ITrxManager.class);
	private final IDocTypeDAO docTypeDAO = Services.get(IDocTypeDAO.class);

	@Override
	public I_C_Payment getById(@NonNull final PaymentId paymentId)
	{
		return paymentDAO.getById(paymentId);
	}

	@Override
	public List<I_C_Payment> getByIds(@NonNull final Set<PaymentId> paymentIds)
	{
		return paymentDAO.getByIds(paymentIds);
	}

	@Override
	public void save(@NonNull final I_C_Payment payment)
	{
		paymentDAO.save(payment);
	}

	@Override
	public ImmutableSet<PaymentId> getPaymentIds(@NonNull final PaymentQuery query)
	{
		return paymentDAO.retrievePaymentIds(query);
	}

	private CurrencyId fetchC_Currency_Invoice_ID(final I_C_Payment payment)
	{
		final int C_Invoice_ID = payment.getC_Invoice_ID();
		final int C_Order_ID = payment.getC_Order_ID();

		CurrencyId C_Currency_Invoice_ID = null;

		if (C_Invoice_ID > 0)
		{
			C_Currency_Invoice_ID = CurrencyId.ofRepoIdOrNull(payment.getC_Invoice().getC_Currency_ID());
		} // get Invoice Info
		else if (C_Order_ID > 0)
		{
			C_Currency_Invoice_ID = CurrencyId.ofRepoIdOrNull(payment.getC_Order().getC_Currency_ID());
		}
		logger.debug("C_Currency_Invoice_ID = " + C_Currency_Invoice_ID + ", C_Invoice_ID=" + C_Invoice_ID);

		return C_Currency_Invoice_ID;
	}

	/**
	 * Get Open Amount invoice
	 *
	 * @param creditMemoAdjusted true if we want to get absolute values for Credit Memos
	 */
	private BigDecimal fetchOpenAmount(final I_C_Payment payment, final boolean creditMemoAdjusted)
	{
		BigDecimal InvoiceOpenAmt = ZERO;

		final int C_Invoice_ID = payment.getC_Invoice_ID();

		if (C_Invoice_ID > 0)
		{
			InvoiceOpenAmt = paymentDAO.getInvoiceOpenAmount(payment, creditMemoAdjusted);
		}

		final CurrencyId currencyId = CurrencyId.ofRepoIdOrNull(payment.getC_Currency_ID());
		final CurrencyId invoiceCurrencyId = fetchC_Currency_Invoice_ID(payment);
		final LocalDate ConvDate = TimeUtil.asLocalDate(payment.getDateTrx());
		final CurrencyConversionTypeId conversionTypeId = CurrencyConversionTypeId.ofRepoIdOrNull(payment.getC_ConversionType_ID());
		final ClientId clientId = ClientId.ofRepoId(payment.getAD_Client_ID());
		final OrgId orgId = OrgId.ofRepoId(payment.getAD_Org_ID());

		// Get Currency Rate
		BigDecimal CurrencyRate = BigDecimal.ONE;
		if (currencyId != null
				&& invoiceCurrencyId != null
				&& !currencyId.equals(invoiceCurrencyId))
		{
			CurrencyRate = currencyBL.getRate(
					invoiceCurrencyId,
					currencyId,
					ConvDate,
					conversionTypeId,
					clientId,
					orgId);
			if (CurrencyRate == null || CurrencyRate.compareTo(ZERO) == 0)
			{
				throw new AdempiereException("NoCurrencyConversion");
			}

			//
			final CurrencyPrecision precision = currencyDAO.getStdPrecision(currencyId);
			InvoiceOpenAmt = precision.round(InvoiceOpenAmt.multiply(CurrencyRate));
		}

		return InvoiceOpenAmt;
	}

	@Override
	public void updateAmounts(final I_C_Payment payment, final String colName, boolean creditMemoAdjusted)
	{
		final int C_Invoice_ID = payment.getC_Invoice_ID();
		final int C_Order_ID = payment.getC_Order_ID();
		final DocStatus docStatus = DocStatus.ofNullableCodeOrUnknown(payment.getDocStatus());

		// New Payment
		if (payment.getC_Payment_ID() <= 0 && payment.getC_BPartner_ID() <= 0 && C_Invoice_ID <= 0)
		{
			return;
		}

		// Changed Column IsOverUnderPayment
		if (I_C_Payment.COLUMNNAME_IsOverUnderPayment.equals(colName))
		{
			onIsOverUnderPaymentChange(payment, creditMemoAdjusted);
		}
		// set all amount on zero if no order nor invoice is set
		else if (C_Invoice_ID <= 0 && C_Order_ID <= 0)
		{
			if (payment.getDiscountAmt().signum() != 0)
			{
				payment.setDiscountAmt(ZERO);
			}
			if (payment.getWriteOffAmt().signum() != 0)
			{
				payment.setWriteOffAmt(ZERO);
			}
			if (payment.getOverUnderAmt().signum() != 0)
			{
				payment.setOverUnderAmt(ZERO);
			}
		}
		// Changed Column C_Currency_ID or C_ConversionType_ID
		else if (I_C_Payment.COLUMNNAME_C_Currency_ID.equals(colName) || I_C_Payment.COLUMNNAME_C_ConversionType_ID.equals(colName))
		{
			onCurrencyChange(payment);
		}
		// Changed Column PayAmt
		else if (I_C_Payment.COLUMNNAME_PayAmt.equals(colName))
		{
			onPayAmtChange(payment, true);
		}
		// calculate PayAmt
		else if (docStatus.isDrafted())
		{
			final BigDecimal InvoiceOpenAmt = fetchOpenAmount(payment, creditMemoAdjusted);
			final BigDecimal DiscountAmt = payment.getDiscountAmt();
			final BigDecimal WriteOffAmt = payment.getWriteOffAmt();
			final BigDecimal OverUnderAmt = payment.getOverUnderAmt();

			final BigDecimal PayAmt = InvoiceOpenAmt.subtract(DiscountAmt).subtract(WriteOffAmt).subtract(OverUnderAmt);
			payment.setPayAmt(PayAmt);
		}

	}

	@Override
	public void onIsOverUnderPaymentChange(final I_C_Payment payment, boolean creditMemoAdjusted)
	{
		payment.setOverUnderAmt(ZERO);
		final DocStatus docStatus = DocStatus.ofNullableCodeOrUnknown(payment.getDocStatus());

		if (docStatus.isDrafted())
		{
			final BigDecimal InvoiceOpenAmt = fetchOpenAmount(payment, creditMemoAdjusted);
			final BigDecimal DiscountAmt = payment.getDiscountAmt();
			final BigDecimal PayAmt = payment.getPayAmt();

			if (payment.isOverUnderPayment())
			{
				final BigDecimal OverUnderAmt = InvoiceOpenAmt.subtract(PayAmt).subtract(DiscountAmt);
				payment.setWriteOffAmt(ZERO);
				payment.setOverUnderAmt(OverUnderAmt);
			}
			else
			{
				final BigDecimal WriteOffAmt = InvoiceOpenAmt.subtract(PayAmt).subtract(DiscountAmt);
				payment.setWriteOffAmt(WriteOffAmt);
				payment.setOverUnderAmt(ZERO);
			}
		}
	}

	@Override
	public void onCurrencyChange(final I_C_Payment payment)
	{
		final int C_Invoice_ID = payment.getC_Invoice_ID();
		final int C_Order_ID = payment.getC_Order_ID();
		// Get Currency Info
		final CurrencyId currencyId = CurrencyId.ofRepoIdOrNull(payment.getC_Currency_ID());
		final CurrencyId invoiceCurrencyId = fetchC_Currency_Invoice_ID(payment);
		final LocalDate convDate = TimeUtil.asLocalDate(payment.getDateTrx());
		final CurrencyConversionTypeId conversionTypeId = CurrencyConversionTypeId.ofRepoIdOrNull(payment.getC_ConversionType_ID());
		final ClientId clientId = ClientId.ofRepoId(payment.getAD_Client_ID());
		final OrgId orgId = OrgId.ofRepoId(payment.getAD_Org_ID());

		// Get Currency Rate
		BigDecimal currencyRate = BigDecimal.ONE;
		if (currencyId != null
				&& invoiceCurrencyId != null
				&& !currencyId.equals(invoiceCurrencyId))
		{
			currencyRate = currencyBL.getRate(
					invoiceCurrencyId,
					currencyId,
					convDate,
					conversionTypeId,
					clientId,
					orgId);
			if (currencyRate == null || currencyRate.signum() == 0)
			{
				final CurrencyConversionContext conversionCtx = currencyBL.createCurrencyConversionContext(
						convDate,
						conversionTypeId,
						clientId,
						orgId);
				throw new NoCurrencyRateFoundException(conversionCtx, invoiceCurrencyId, currencyId);
			}
		}

		BigDecimal PayAmt = payment.getPayAmt();
		BigDecimal DiscountAmt = payment.getDiscountAmt();
		BigDecimal WriteOffAmt = payment.getWriteOffAmt();
		BigDecimal OverUnderAmt = payment.getOverUnderAmt();

		final CurrencyPrecision precision = currencyId != null
				? currencyDAO.getStdPrecision(currencyId)
				: CurrencyPrecision.TWO;

		PayAmt = precision.round(PayAmt.multiply(currencyRate));
		payment.setPayAmt(PayAmt);

		DiscountAmt = precision.round(DiscountAmt.multiply(currencyRate));
		payment.setDiscountAmt(DiscountAmt);

		WriteOffAmt = precision.round(WriteOffAmt.multiply(currencyRate));
		payment.setWriteOffAmt(WriteOffAmt);

		OverUnderAmt = precision.round(OverUnderAmt.multiply(currencyRate));
		payment.setOverUnderAmt(OverUnderAmt);

		// No Invoice or Order - Set Discount, Witeoff, Under/Over to 0
		if (C_Invoice_ID <= 0 && C_Order_ID <= 0)
		{
			if (ZERO.compareTo(DiscountAmt) != 0)
			{
				payment.setDiscountAmt(ZERO);
			}

			if (ZERO.compareTo(WriteOffAmt) != 0)
			{
				payment.setWriteOffAmt(ZERO);
			}
			if (ZERO.compareTo(OverUnderAmt) != 0)
			{
				payment.setOverUnderAmt(ZERO);
			}
		}

	}

	@Override
	public void onPayAmtChange(final I_C_Payment payment, boolean creditMemoAdjusted)
	{
		final DocStatus docStatus = DocStatus.ofNullableCodeOrUnknown(payment.getDocStatus());
		if (docStatus.isDrafted())
		{
			final BigDecimal DiscountAmt = payment.getDiscountAmt();
			final BigDecimal PayAmt = payment.getPayAmt();
			final BigDecimal InvoiceOpenAmt = fetchOpenAmount(payment, creditMemoAdjusted);
			BigDecimal WriteOffAmt = payment.getWriteOffAmt();
			BigDecimal OverUnderAmt = payment.getOverUnderAmt();

			if (payment.isOverUnderPayment())
			{
				OverUnderAmt = InvoiceOpenAmt.subtract(PayAmt).subtract(DiscountAmt).subtract(WriteOffAmt);
				payment.setOverUnderAmt(OverUnderAmt);
			}

			WriteOffAmt = InvoiceOpenAmt.subtract(PayAmt).subtract(DiscountAmt).subtract(OverUnderAmt);
			payment.setWriteOffAmt(WriteOffAmt);
			payment.setDiscountAmt(DiscountAmt);
		}

	}

	@Override
	public DefaultPaymentBuilder newInboundReceiptBuilder()
	{
		return DefaultPaymentBuilder.newInboundReceiptBuilder();
	}

	@Override
	public DefaultPaymentBuilder newOutboundPaymentBuilder()
	{
		return DefaultPaymentBuilder.newOutboundPaymentBuilder();
	}

	@Override
	public DefaultPaymentBuilder newBuilderOfInvoice(@NonNull final I_C_Invoice invoice)
	{
		return DefaultPaymentBuilder.newBuilderOfInvoice(invoice);
	}

	@Override
	public boolean isMatchInvoice(final I_C_Payment payment, final I_C_Invoice invoice)
	{
		final List<I_C_AllocationLine> allocations = paymentDAO.retrieveAllocationLines(payment);
		final List<I_C_Invoice> invoices = new ArrayList<>();
		for (final I_C_AllocationLine alloc : allocations)
		{
			invoices.add(alloc.getC_Invoice());
		}

		for (final I_C_Invoice inv : invoices)
		{
			if (inv.getC_Invoice_ID() == invoice.getC_Invoice_ID())
			{
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean testAllocation(final I_C_Payment payment)
	{
		BigDecimal alloc = paymentDAO.getAllocatedAmt(payment);
		final boolean hasAllocations = alloc != null; // metas: tsa: 01955
		if (alloc == null)
		{
			alloc = ZERO;
		}

		BigDecimal total = payment.getPayAmt();

		// metas: tsa: begin: 01955:
		// If is an zero payment and it has no allocations and the AutoPayZeroAmt flag is not set
		// then don't touch the payment
		if (total.signum() == 0
				&& !hasAllocations
				&& !sysConfigBL.getBooleanValue("org.compiere.model.MInvoice.AutoPayZeroAmt", true, payment.getAD_Client_ID()))
		{
			// don't touch the IsAllocated flag, return not changed
			return false;
		}
		// metas: tsa: end: 01955
		if (!payment.isReceipt())
		{
			total = total.negate();
		}
		boolean test = total.compareTo(alloc) == 0;
		boolean change = test != payment.isAllocated();
		if (change)
		{
			payment.setIsAllocated(test);
		}

		logger.debug("Allocated={} ({}={})", test, alloc, total);
		return change;
	}	// testAllocation

	@Override
	public boolean isCashTrx(I_C_Payment payment)
	{
		final TenderType tenderType = TenderType.ofCode(payment.getTenderType());
		return tenderType.isCash();
	}

	@Override
	public I_C_AllocationHdr paymentWriteOff(final I_C_Payment payment, final BigDecimal writeOffAmt, final Date date)
	{
		Check.assumeNotNull(payment, "payment not null");
		Check.assume(writeOffAmt != null && writeOffAmt.signum() != 0, "WriteOffAmt != 0 but it was {}", writeOffAmt);
		Check.assumeNotNull(date, "date not null");

		final Properties ctx = InterfaceWrapperHelper.getCtx(payment);
		final Timestamp dateTS = TimeUtil.asTimestamp(date);

		final Mutable<I_C_AllocationHdr> allocHdrRef = new Mutable<>();
		trxManager.run(ITrx.TRXNAME_ThreadInherited, new TrxRunnableAdapter()
		{

			@Override
			public void run(String localTrxName) throws Exception
			{
				final I_C_AllocationHdr allocHdr = allocationBL.newBuilder(PlainContextAware.newWithThreadInheritedTrx(ctx))
						.setAD_Org_ID(payment.getAD_Org_ID())
						.setC_Currency_ID(payment.getC_Currency_ID())
						.setDateAcct(dateTS)
						.setDateTrx(dateTS)
						//
						.addLine()
						.setAD_Org_ID(payment.getAD_Org_ID())
						.setC_BPartner_ID(payment.getC_BPartner_ID())
						.setC_Payment_ID(payment.getC_Payment_ID())
						.setPaymentWriteOffAmt(writeOffAmt)
						.lineDone()
						//
						.createAndComplete();
				allocHdrRef.setValue(allocHdr);
			}
		});

		return allocHdrRef.getValue();
	}

	@Override
	public void updateDiscountAndPayAmtFromInvoiceIfAny(final I_C_Payment payment)
	{
		final InvoiceId invoiceId = InvoiceId.ofRepoIdOrNull(payment.getC_Invoice_ID());
		if (invoiceId == null)
		{
			return;
		}
		final I_C_Invoice invoice = invoiceDAO.getByIdInTrx(invoiceId);
		if (invoice.getC_DocType_ID() <= 0)
		{
			return;
		}
		final I_C_DocType docType = docTypeDAO.getById(DocTypeId.ofRepoIdOrNull(invoice.getC_DocType_ID()));
		paymentDAO.updateDiscountAndPayment(payment, invoice.getC_Invoice_ID(), docType);
	}

	@Override
	public void markNotReconciled(@NonNull final Collection<PaymentId> paymentIds)
	{
		if (paymentIds.isEmpty())
		{
			return;
		}

		final List<I_C_Payment> payments = paymentDAO.getByIds(ImmutableSet.copyOf(paymentIds));
		for (final I_C_Payment payment : payments)
		{
			payment.setIsReconciled(false);
			paymentDAO.save(payment);
		}
	}

	@Override
	public void markReconciled(@NonNull final Collection<PaymentId> paymentIds)
	{
		if (paymentIds.isEmpty())
		{
			return;
		}

		final List<I_C_Payment> payments = paymentDAO.getByIds(ImmutableSet.copyOf(paymentIds));
		for (final I_C_Payment payment : payments)
		{
			markReconciledAndSave(payment);
		}
	}

	@Override
	public void markReconciledAndSave(@NonNull final I_C_Payment payment)
	{
		payment.setIsReconciled(true);
		paymentDAO.save(payment);
	}
}
