package de.metas.contracts.refund;

import static de.metas.util.NumberUtils.stripTrailingDecimalZeros;
import static org.adempiere.model.InterfaceWrapperHelper.getValueOverrideOrValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import javax.annotation.Nullable;

import org.compiere.model.I_C_Currency;
import org.compiere.util.TimeUtil;
import org.springframework.stereotype.Service;

import com.google.common.annotations.VisibleForTesting;

import de.metas.bpartner.BPartnerId;
import de.metas.invoice.InvoiceScheduleRepository;
import de.metas.invoicecandidate.InvoiceCandidateId;
import de.metas.invoicecandidate.model.I_C_Invoice_Candidate;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.product.ProductId;
import de.metas.quantity.Quantity;
import lombok.NonNull;

/*
 * #%L
 * de.metas.contracts
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Service
public class AssignableInvoiceCandidateFactory
{
	private final AssignmentToRefundCandidateRepository assignmentToRefundCandidateRepository;

	@VisibleForTesting
	public static AssignableInvoiceCandidateFactory newForUnitTesting()
	{
		final InvoiceScheduleRepository invoiceScheduleRepository = new InvoiceScheduleRepository();
		final RefundConfigRepository refundConfigRepository = new RefundConfigRepository(invoiceScheduleRepository);
		final RefundContractRepository refundContractRepository = new RefundContractRepository(refundConfigRepository);
		final AssignmentAggregateService assignmentAggregateService = new AssignmentAggregateService(refundConfigRepository);
		final RefundInvoiceCandidateFactory refundInvoiceCandidateFactory = new RefundInvoiceCandidateFactory(refundContractRepository, assignmentAggregateService);
		final RefundInvoiceCandidateRepository refundInvoiceCandidateRepository = new RefundInvoiceCandidateRepository(refundContractRepository, refundInvoiceCandidateFactory);
		final AssignmentToRefundCandidateRepository assignmentToRefundCandidateRepository = new AssignmentToRefundCandidateRepository(refundInvoiceCandidateRepository);

		return new AssignableInvoiceCandidateFactory(assignmentToRefundCandidateRepository);
	}

	public AssignableInvoiceCandidateFactory(@NonNull final AssignmentToRefundCandidateRepository assignmentToRefundCandidateRepository)
	{
		this.assignmentToRefundCandidateRepository = assignmentToRefundCandidateRepository;
	}

	/** Note: does not load&include {@link AssignmentToRefundCandidate}s; those need to be retrieved using {@link AssignmentToRefundCandidateRepository}. */
	public AssignableInvoiceCandidate ofRecord(@Nullable final I_C_Invoice_Candidate assignableRecord)
	{
		final InvoiceCandidateId invoiceCandidateId = InvoiceCandidateId.ofRepoId(assignableRecord.getC_Invoice_Candidate_ID());

		final Timestamp invoicableFromDate = getValueOverrideOrValue(assignableRecord, I_C_Invoice_Candidate.COLUMNNAME_DateToInvoice);
		final BigDecimal moneyAmount = assignableRecord
				.getNetAmtInvoiced()
				.add(assignableRecord.getNetAmtToInvoice());

		final I_C_Currency currencyRecord = assignableRecord.getC_Currency();
		final CurrencyId currencyId = CurrencyId.ofRepoId(currencyRecord.getC_Currency_ID());
		final int precision = currencyRecord.getStdPrecision();

		final Money money = Money.of(stripTrailingDecimalZeros(moneyAmount), currencyId);

		final Quantity quantity = Quantity.of(
				assignableRecord.getQtyToInvoice().add(stripTrailingDecimalZeros(assignableRecord.getQtyInvoiced())),
				assignableRecord.getM_Product().getC_UOM());

		final List<AssignmentToRefundCandidate> assignments = assignmentToRefundCandidateRepository.getAssignmentsByAssignableCandidateId(invoiceCandidateId);

		final AssignableInvoiceCandidate invoiceCandidate = AssignableInvoiceCandidate.builder()
				.repoId(invoiceCandidateId)
				.bpartnerId(BPartnerId.ofRepoId(assignableRecord.getBill_BPartner_ID()))
				.invoiceableFrom(TimeUtil.asLocalDate(invoicableFromDate))
				.money(money)
				.precision(precision)
				.quantity(quantity)
				.productId(ProductId.ofRepoId(assignableRecord.getM_Product_ID()))
				.assignmentsToRefundCandidates(assignments)
				.build();

		return invoiceCandidate;
	}
}
