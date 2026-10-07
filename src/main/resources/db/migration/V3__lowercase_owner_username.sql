alter table app_users add constraint app_users_username_lowercase check (username = lower(username));
