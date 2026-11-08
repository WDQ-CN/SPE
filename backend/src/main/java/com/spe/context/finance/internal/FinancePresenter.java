package com.spe.context.finance.internal;

import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.GlAccountBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 财务视图装配：科目/公司/操作人名称批量取，避免逐行回查。 */
@Component
@RequiredArgsConstructor
public class FinancePresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final JournalLineMapper journalLineMapper;

  @Transactional(readOnly = true)
  public JournalEntryView entry(JournalEntry entry) {
    return entry(entry, journalLineMapper.findByEntryId(entry.getId()));
  }

  @Transactional(readOnly = true)
  public JournalEntryView entry(JournalEntry entry, List<JournalLine> lines) {
    CompanyBrief company = masterDataQueryFacade.findCompany(entry.getCompanyId()).orElse(null);
    UserBrief creator = userQueryFacade.findById(entry.getCreatedBy()).orElse(null);
    Map<Long, GlAccountBrief> accounts =
        masterDataQueryFacade.findAccounts(
            lines.stream().map(JournalLine::getAccountId).toList()).stream()
            .collect(Collectors.toMap(GlAccountBrief::id, Function.identity()));
    return new JournalEntryView(
        entry.getId(),
        entry.getDocNo(),
        entry.getCompanyId(),
        company == null ? null : company.name(),
        entry.getEntryDate(),
        entry.getSourceType(),
        entry.getSourceId(),
        entry.getCurrency(),
        entry.getDescription(),
        entry.getTotalAmount(),
        entry.getStatus(),
        entry.getCreatedBy(),
        creator == null ? null : creator.name(),
        entry.getPostedAt(),
        entry.getCreatedAt(),
        lines.stream().map(line -> line(line, accounts)).toList());
  }

  @Transactional(readOnly = true)
  public List<JournalEntrySummaryView> entries(List<JournalEntry> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(JournalEntry::getCompanyId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              return new JournalEntrySummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getEntryDate(),
                  row.getSourceType(),
                  row.getSourceId(),
                  row.getCurrency(),
                  row.getDescription(),
                  row.getTotalAmount(),
                  row.getStatus(),
                  row.getPostedAt());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public TrialBalanceView trialBalance(
      long companyId,
      LocalDate from,
      LocalDate to,
      List<JournalLineMapper.AccountTotals> totals) {
    Map<Long, GlAccountBrief> accounts =
        masterDataQueryFacade.findAccounts(
            totals.stream().map(JournalLineMapper.AccountTotals::accountId).toList()).stream()
            .collect(Collectors.toMap(GlAccountBrief::id, Function.identity()));
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    List<TrialBalanceRowView> rows = new java.util.ArrayList<>();
    for (JournalLineMapper.AccountTotals total : totals) {
      GlAccountBrief account = accounts.get(total.accountId());
      totalDebit = totalDebit.add(total.debitTotal());
      totalCredit = totalCredit.add(total.creditTotal());
      rows.add(
          new TrialBalanceRowView(
              total.accountId(),
              account == null ? null : account.code(),
              account == null ? null : account.name(),
              total.debitTotal(),
              total.creditTotal(),
              total.debitTotal().subtract(total.creditTotal())));
    }
    return new TrialBalanceView(
        companyId, from, to, rows, totalDebit, totalCredit, totalDebit.compareTo(totalCredit) == 0);
  }

  private Map<Long, CompanyBrief> companiesOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findCompany(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(CompanyBrief::id, Function.identity()));
  }

  private static JournalLineView line(JournalLine line, Map<Long, GlAccountBrief> accounts) {
    GlAccountBrief account = accounts.get(line.getAccountId());
    return new JournalLineView(
        line.getId(),
        line.getLineNo(),
        line.getAccountId(),
        account == null ? null : account.code(),
        account == null ? null : account.name(),
        line.getDebit(),
        line.getCredit(),
        line.getDescription());
  }
}
