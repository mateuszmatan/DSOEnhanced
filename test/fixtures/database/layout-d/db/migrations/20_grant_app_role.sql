BEGIN
  EXECUTE IMMEDIATE 'GRANT SELECT ON orders TO ${app_role}';
  EXECUTE IMMEDIATE 'GRANT SELECT ON v_order_totals TO ${app_role}';
END;
/
